package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.PlayerScore;
import com.github.shikejing.reward.service.ServiceException;
import com.github.shikejing.reward.service.ServiceException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 结算前修订：撤销 + 替换，窗口边界行为确定。 */
class RevisionTest {

    @TempDir Path dir;

    private static List<PlayerScore> scores(long a, long b) {
        return List.of(new PlayerScore("p1", a), new PlayerScore("p2", b));
    }

    @Test
    void revokeAndReplaceWithinWindow() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        stack.clock().advance(10_000); // 窗口内
        stack.matches().revokeReport("m1", 1);
        stack.matches().submitReport("m1", 2, scores(80, 90)); // 替换结果
        var conclusion = stack.matches().conclusionOf("m1");
        assertEquals(2, conclusion.reportSeq());
        assertEquals(90, conclusion.scores().stream()
                .filter(s -> s.playerId().equals("p2")).findFirst().orElseThrow().score());
        // 被撤销的上报仍可查到
        var history = stack.matches().historyOf("m1");
        assertEquals(2, history.size());
        assertTrue(history.stream().anyMatch(r -> r.reportSeq() == 1 && r.revoked()));
    }

    @Test
    void windowBoundaryIsInclusiveAndDeterministic() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        stack.clock().advance(60_000); // 恰好到达边界 firstReportAt + window
        stack.matches().submitReport("m1", 2, scores(1, 2)); // 边界上：允许（闭区间）
        assertEquals(2, stack.matches().conclusionOf("m1").reportSeq());
        stack.clock().advance(1); // 越过边界 1ms
        ServiceException e = assertThrows(ServiceException.class,
                () -> stack.matches().submitReport("m1", 3, scores(3, 4)));
        assertEquals(Reason.REVISION_WINDOW_CLOSED, e.reason());
        assertEquals(2, stack.matches().conclusionOf("m1").reportSeq());
    }

    @Test
    void revokeAfterWindowClosedRejected() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        stack.clock().advance(60_001);
        ServiceException e = assertThrows(ServiceException.class,
                () -> stack.matches().revokeReport("m1", 1));
        assertEquals(Reason.REVISION_WINDOW_CLOSED, e.reason());
    }

    @Test
    void noRevisionAfterSettlementAndGrantsSurvive() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        stack.settlements().settle("m1");
        stack.settlements().grant("m1");
        // 结算后撤销被拒绝
        ServiceException e = assertThrows(ServiceException.class,
                () -> stack.matches().revokeReport("m1", 1));
        assertEquals(Reason.ALREADY_SETTLED, e.reason());
        // 已发放奖励不被回退
        var grants = stack.store().grantsOf("m1");
        assertEquals(2, grants.size());
        assertEquals(150, grants.stream().mapToLong(g -> g.amount()).sum());
        // 结算后替换上报不影响已发布结论
        stack.matches().submitReport("m1", 2, scores(1, 999));
        assertEquals(1, stack.matches().conclusionOf("m1").reportSeq());
        assertEquals(150, stack.store().grantsOf("m1").stream().mapToLong(g -> g.amount()).sum());
    }
}
