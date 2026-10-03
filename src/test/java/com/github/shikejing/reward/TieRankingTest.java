package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.PlayerScore;
import com.github.shikejing.reward.domain.Model.RewardLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.shikejing.reward.domain.Model.TiePolicy.EACH_FULL;
import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 名次并列处理与结算确定性。 */
class TieRankingTest {

    @TempDir Path dir;

    private static List<PlayerScore> field() {
        // a,b 并列第一(90)，c 第三(70)，d,e 并列第四(50)
        return List.of(new PlayerScore("a", 90), new PlayerScore("b", 90),
                new PlayerScore("c", 70), new PlayerScore("d", 50), new PlayerScore("e", 50));
    }

    private static Map<String, RewardLine> byPlayer(List<RewardLine> lines) {
        return lines.stream().collect(Collectors.toMap(RewardLine::playerId, l -> l));
    }

    @Test
    void splitSumSharesOccupiedRanks() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        var lines = byPlayer(stack.settlements().preview(field()));
        // a,b 占用名次1,2 -> (100+50)/2 = 75 各
        assertEquals(1, lines.get("a").rank());
        assertEquals(75, lines.get("a").amount());
        assertEquals(75, lines.get("b").amount());
        // c 名次3 -> 20
        assertEquals(3, lines.get("c").rank());
        assertEquals(20, lines.get("c").amount());
        // d,e 占用名次4,5 -> 0+0
        assertEquals(4, lines.get("d").rank());
        assertEquals(0, lines.get("d").amount());
    }

    @Test
    void splitSumRemainderDeterministic() {
        // 3 人并列第一，占用名次1,2,3 -> 100+50+20=170，170/3=56 余2，
        // 余数按 playerId 字典序前 2 名各 +1
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        var lines = stack.settlements().preview(List.of(
                new PlayerScore("c", 90), new PlayerScore("a", 90), new PlayerScore("b", 90)));
        var m = byPlayer(lines);
        assertEquals(57, m.get("a").amount());
        assertEquals(57, m.get("b").amount());
        assertEquals(56, m.get("c").amount());
        assertEquals(170, lines.stream().mapToLong(RewardLine::amount).sum()); // 总额守恒
    }

    @Test
    void eachFullGivesFullRankAmount() {
        var stack = TestSupport.newStack(dir, 60_000, EACH_FULL, 0);
        var lines = byPlayer(stack.settlements().preview(field()));
        assertEquals(100, lines.get("a").amount());
        assertEquals(100, lines.get("b").amount());
        assertEquals(20, lines.get("c").amount());
    }

    @Test
    void settlementIsDeterministicAcrossRuns() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        List<RewardLine> first = stack.settlements().preview(field());
        for (int i = 0; i < 50; i++) {
            assertEquals(first, stack.settlements().preview(field()), "第 " + i + " 次重跑不一致");
        }
    }

    @Test
    void settleTwiceReturnsSameCommittedVersion() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        stack.matches().submitReport("m1", 1, field());
        var s1 = stack.settlements().settle("m1");
        var s2 = stack.settlements().settle("m1");
        assertEquals(s1, s2); // 同一对局不会被结算两次
    }
}
