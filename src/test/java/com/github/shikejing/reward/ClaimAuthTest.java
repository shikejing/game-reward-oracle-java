package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.ClaimFailure;
import com.github.shikejing.reward.domain.Model.PlayerScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 越权领取与领取幂等。 */
class ClaimAuthTest {

    @TempDir Path dir;

    private TestSupport.Stack grantedStack() {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        stack.matches().submitReport("m1", 1,
                List.of(new PlayerScore("alice", 90), new PlayerScore("bob", 80)));
        stack.settlements().settle("m1");
        stack.settlements().grant("m1");
        return stack;
    }

    @Test
    void forbiddenClaimRejectedWithDistinctReason() {
        var stack = grantedStack();
        var r = stack.claims().claim("m1", "alice", "mallory"); // 冒领 alice 的奖励
        assertFalse(r.ok());
        assertEquals(ClaimFailure.FORBIDDEN, r.failure());
        assertTrue(stack.store().claimOf("m1", "alice").isEmpty()); // 未产生领取记录
    }

    @Test
    void unknownGrantGivesNotFoundDistinctFromForbidden() {
        var stack = grantedStack();
        var r = stack.claims().claim("m1", "nobody", "nobody");
        assertFalse(r.ok());
        assertEquals(ClaimFailure.NOT_FOUND, r.failure());
        assertNotEquals(ClaimFailure.FORBIDDEN, r.failure());
    }

    @Test
    void repeatClaimReturnsFirstResult() {
        var stack = grantedStack();
        var first = stack.claims().claim("m1", "alice", "alice");
        assertTrue(first.ok());
        for (int i = 0; i < 5; i++) {
            var again = stack.claims().claim("m1", "alice", "alice");
            assertTrue(again.ok());
            assertEquals(first.record(), again.record()); // 只返回首次结果
        }
        assertEquals(1, stack.store().grantsOf("m1").size() >= 1
                ? stack.store().claimOf("m1", "alice").stream().count() : 0);
    }
}
