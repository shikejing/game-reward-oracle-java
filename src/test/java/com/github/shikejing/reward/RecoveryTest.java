package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.PlayerScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 重启与中断恢复：已提交完整还原，半份记录不残留，不重复发放。 */
class RecoveryTest {

    @TempDir Path dir;

    private static List<PlayerScore> scores() {
        return List.of(new PlayerScore("p1", 90), new PlayerScore("p2", 80));
    }

    @Test
    void committedStateFullyRestoredAfterRestart() {
        var s1 = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        s1.matches().submitReport("m1", 1, scores());
        s1.settlements().settle("m1");
        s1.settlements().grant("m1");
        s1.claims().claim("m1", "p1", "p1");
        // m2 只上报未结算：恢复后应可区分
        s1.matches().submitReport("m2", 1, scores());

        var s2 = TestSupport.restart(dir, 60_000, SPLIT_SUM, 2_000);
        // 已提交的结算与账目完整还原
        assertTrue(s2.store().settlementOf("m1").isPresent());
        assertEquals(2, s2.store().grantsOf("m1").size());
        assertTrue(s2.store().claimOf("m1", "p1").isPresent());
        // 重启后重复发放/领取幂等：返回首次已提交版本
        var grantsAgain = s2.settlements().grant("m1");
        assertEquals(2, grantsAgain.size());
        var claimAgain = s2.claims().claim("m1", "p1", "p1");
        assertTrue(claimAgain.ok());
        assertEquals(100, claimAgain.record().amount());
        // 未完成的对局（m2）无结算记录，可继续走完链路
        assertTrue(s2.store().settlementOf("m2").isEmpty());
        s2.settlements().settle("m2");
        assertEquals(2, s2.settlements().grant("m2").size());
    }

    @Test
    void tornTailOfJournalIsDiscarded() throws Exception {
        var s1 = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        s1.matches().submitReport("m1", 1, scores());
        s1.settlements().settle("m1");
        // 模拟崩溃：journal 末尾残留半行未提交数据
        Files.writeString(dir.resolve("journal.log"), "{\"type\":\"GRANTS\",\"data\":{\"matchId\":\"m1\",\"gra",
                StandardOpenOption.APPEND);

        var s2 = TestSupport.restart(dir, 60_000, SPLIT_SUM, 2_000);
        // 半份记录不残留：结算在，发放不在
        assertTrue(s2.store().settlementOf("m1").isPresent());
        assertTrue(s2.store().grantsOf("m1").isEmpty());
        // 恢复后可正常补发，且只发一次
        assertEquals(2, s2.settlements().grant("m1").size());
        assertEquals(2, s2.settlements().grant("m1").size());
    }

    @Test
    void noDoubleGrantAcrossRestart() {
        var s1 = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        s1.matches().submitReport("m1", 1, scores());
        s1.settlements().settle("m1");
        s1.settlements().grant("m1");

        var s2 = TestSupport.restart(dir, 60_000, SPLIT_SUM, 2_000);
        var grants = s2.settlements().grant("m1"); // 重启后再发：必须幂等
        assertEquals(2, grants.size());
        assertEquals(150, grants.stream().mapToLong(g -> g.amount()).sum());
        // journal 中 GRANTS 事件只有一条 -> 不可能重复发放
        long grantEvents = s2.store().grantsOf("m1").size();
        assertEquals(2, grantEvents);
    }
}
