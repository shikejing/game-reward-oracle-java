package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.MatchReport;
import com.github.shikejing.reward.domain.Model.PlayerScore;
import com.github.shikejing.reward.service.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 乱序、重复、冲突上报的收敛行为。 */
class ReportConvergenceTest {

    @TempDir Path dir;

    private static List<PlayerScore> scores(long... s) {
        return List.of(new PlayerScore("p1", s[0]), new PlayerScore("p2", s[1]));
    }

    @Test
    void outOfOrderReportsConvergeToMaxSeq() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 2, scores(90, 80));
        stack.matches().submitReport("m1", 1, scores(10, 20)); // 乱序迟到（窗口内，留痕）
        MatchReport c = stack.matches().conclusionOf("m1");
        assertEquals(2, c.reportSeq());
        assertEquals(2, stack.matches().historyOf("m1").size());
    }

    @Test
    void duplicateReportIsIdempotent() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        MatchReport first = stack.matches().submitReport("m1", 1, scores(90, 80));
        MatchReport dup = stack.matches().submitReport("m1", 1, scores(90, 80));
        assertSame(first, dup);
        assertEquals(1, stack.matches().historyOf("m1").size());
    }

    @Test
    void conflictingSameSeqRejected() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        ServiceException e = assertThrows(ServiceException.class,
                () -> stack.matches().submitReport("m1", 1, scores(1, 2)));
        assertEquals(ServiceException.Reason.DUPLICATE_REPORT_CONFLICT, e.reason());
    }

    @Test
    void lateReportAfterSettlementDoesNotRewriteConclusion() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 1_000);
        stack.matches().submitReport("m1", 1, scores(90, 80));
        stack.settlements().settle("m1");
        stack.settlements().grant("m1");
        // 迟到上报：留痕但结论冻结
        stack.matches().submitReport("m1", 2, scores(1, 999));
        assertEquals(1, stack.matches().conclusionOf("m1").reportSeq());
        assertEquals(1, stack.store().settlementOf("m1").orElseThrow().reportSeq());
        // 已发放记录不受迟到上报影响
        var grants = stack.store().grantsOf("m1");
        assertEquals(2, grants.size());
        assertEquals(100, grants.stream().filter(g -> g.playerId().equals("p1")).findFirst().orElseThrow().amount());
    }
}
