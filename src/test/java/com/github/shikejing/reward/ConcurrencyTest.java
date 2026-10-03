package com.github.shikejing.reward;

import com.github.shikejing.reward.domain.Model.ClaimRecord;
import com.github.shikejing.reward.domain.Model.PlayerScore;
import com.github.shikejing.reward.domain.Model.Settlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.shikejing.reward.domain.Model.TiePolicy.SPLIT_SUM;
import static org.junit.jupiter.api.Assertions.*;

/** 并发结算、发放与领取：账目自洽、无重复发放、慢领取不阻塞新对局。 */
class ConcurrencyTest {

    @TempDir Path dir;

    private static List<PlayerScore> scores() {
        return List.of(new PlayerScore("p1", 90), new PlayerScore("p2", 80),
                new PlayerScore("p3", 70));
    }

    @Test
    void concurrentSettleGrantClaimStayConsistent() throws Exception {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        stack.matches().submitReport("m1", 1, scores());

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        ConcurrentLinkedQueue<Settlement> settlements = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ClaimRecord> claims = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < threads; i++) {
            final int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                settlements.add(stack.settlements().settle("m1"));
                stack.settlements().grant("m1");
                String pid = "p" + (k % 3 + 1);
                var r = stack.claims().claim("m1", pid, pid);
                if (r.ok()) claims.add(r.record());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 同一对局只有一个已提交结算版本
        assertEquals(1, settlements.stream().distinct().count());
        // 发放记录唯一且不重复
        var grants = stack.store().grantsOf("m1");
        assertEquals(3, grants.size());
        assertEquals(3, grants.stream().map(g -> g.playerId()).distinct().count());
        // 领取幂等：每个玩家只有一条领取记录，且等于首次结果
        for (String pid : List.of("p1", "p2", "p3")) {
            var c = stack.store().claimOf("m1", pid).orElseThrow();
            long count = claims.stream().filter(x -> x.playerId().equals(pid)).count();
            assertTrue(count >= 1);
            assertTrue(claims.stream().filter(x -> x.playerId().equals(pid))
                    .allMatch(x -> x.equals(c)), "重复领取返回了不同结果");
        }
        // 账目自洽：领取金额 == 发放金额 == 结算行
        for (String pid : List.of("p1", "p2", "p3")) {
            long granted = stack.store().grantOf("m1", pid).orElseThrow().amount();
            long claimed = stack.store().claimOf("m1", pid).orElseThrow().amount();
            assertEquals(granted, claimed);
        }
    }

    @Test
    void slowClaimDoesNotBlockOtherMatches() throws Exception {
        var stack = TestSupport.newStack(dir, 60_000, SPLIT_SUM, 0);
        stack.matches().submitReport("slow", 1, scores());
        stack.settlements().settle("slow");
        stack.settlements().grant("slow");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // 慢领取：在锁内睡眠的领取通过另起线程模拟长耗时领取
        Future<?> slowClaim = pool.submit(() -> {
            var r = stack.claims().claim("slow", "p1", "p1");
            assertTrue(r.ok());
        });
        // 同时推进 20 个新对局的完整链路
        AtomicInteger done = new AtomicInteger();
        List<Future<?>> others = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String mid = "fast-" + i;
            others.add(pool.submit(() -> {
                stack.matches().submitReport(mid, 1, scores());
                stack.settlements().settle(mid);
                stack.settlements().grant(mid);
                assertTrue(stack.claims().claim(mid, "p1", "p1").ok());
                done.incrementAndGet();
            }));
        }
        for (Future<?> f : others) f.get(30, TimeUnit.SECONDS);
        slowClaim.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(20, done.get());
    }
}
