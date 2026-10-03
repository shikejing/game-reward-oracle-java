package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.service.ClaimService;
import com.github.shikejing.gamerewardoraclejava.service.DistributionService;
import com.github.shikejing.gamerewardoraclejava.service.MatchQueryService;
import com.github.shikejing.gamerewardoraclejava.service.OracleService;
import com.github.shikejing.gamerewardoraclejava.service.SettlementService;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributeResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettleResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettlementDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发测试：同一对局只结算一次、领取幂等、并发下账目自洽、
 * 读取到的结论与账目为单个已提交版本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RewardConcurrencyIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("reward").toAbsolutePath());
        r.add("reward.rule.window-ms", () -> "60000");
        r.add("reward.rule.rank-rewards.1", () -> "100");
        r.add("reward.rule.rank-rewards.2", () -> "60");
        r.add("reward.rule.rank-rewards.3", () -> "30");
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
    }

    @Autowired OracleService oracleService;
    @Autowired SettlementService settlementService;
    @Autowired DistributionService distributionService;
    @Autowired ClaimService claimService;
    @Autowired MatchQueryService queryService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute("delete from claims");
        jdbcTemplate.execute("delete from reward_records");
        jdbcTemplate.execute("delete from distributions");
        jdbcTemplate.execute("delete from settlements");
        jdbcTemplate.execute("delete from match_reports");
        jdbcTemplate.execute("delete from matches");
    }

    private List<PlayerResult> fourPlayers() {
        return List.of(new PlayerResult("a", 100), new PlayerResult("b", 90),
                new PlayerResult("c", 80), new PlayerResult("d", 70));
    }

    @Test
    void concurrentSettle_sameMatchExactlyOneSettlement() throws Exception {
        String m = "cs-match";
        oracleService.report(new ReportRequest(m, 1L, fourPlayers()));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<SettleResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return settlementService.settle(m);
            }));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        long uniqueIds = futures.stream().map(f -> {
            try { return f.get().settlementId(); } catch (Exception e) { throw new RuntimeException(e); }
        }).distinct().count();
        assertThat(uniqueIds).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from settlements where match_id = ?",
                Long.class, m)).isEqualTo(1L);
    }

    @Test
    void concurrentClaim_samePlayerExactlyOneClaim() throws Exception {
        String m = "cc-match";
        oracleService.report(new ReportRequest(m, 1L, fourPlayers()));
        settlementService.settle(m);
        distributionService.distribute(m);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<ClaimResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return claimService.claim(m, new ClaimRequest("a"));
            }));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        long uniqueClaimIds = futures.stream().map(f -> {
            try { return f.get().claimId(); } catch (Exception e) { throw new RuntimeException(e); }
        }).distinct().count();
        assertThat(uniqueClaimIds).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from claims where match_id = ?",
                Long.class, m)).isEqualTo(1L);
        // 账目状态为 CLAIMED，金额与结算一致
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select amount, status from reward_records where match_id = ? and player_id = ?", m, "a");
        assertThat(row.get("amount")).isEqualTo(100L);
        assertThat(row.get("status")).isEqualTo("CLAIMED");
    }

    @Test
    void concurrentClaimsAndReads_booksAlwaysSelfConsistent() throws Exception {
        int matchCount = 6;
        for (int i = 0; i < matchCount; i++) {
            String m = "cr-match-" + i;
            oracleService.report(new ReportRequest(m, 1L, fourPlayers()));
            settlementService.settle(m);
            distributionService.distribute(m);
        }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<?>> futures = new ArrayList<>();

        // 写：每局每个参与者领取
        for (int i = 0; i < matchCount; i++) {
            String m = "cr-match-" + i;
            for (String p : List.of("a", "b", "c", "d")) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    claimService.claim(m, new ClaimRequest(p));
                    return null;
                }));
            }
        }
        // 重复领取（幂等压力）
        for (int i = 0; i < matchCount; i++) {
            String m = "cr-match-" + i;
            for (int k = 0; k < 3; k++) {
                futures.add(pool.submit((Callable<Void>) () -> {
                    claimService.claim(m, new ClaimRequest("a"));
                    return null;
                }));
            }
        }
        // 读：并发读取，校验自洽性
        for (int k = 0; k < 12; k++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                for (int i = 0; i < matchCount; i++) {
                    String m = "cr-match-" + i;
                    var detail = queryService.detail(m);
                    assertThat(detail.status().name()).isEqualTo("DISTRIBUTED");
                    assertThat(detail.players()).hasSize(4);
                    var dist = queryService.distribution(m);
                    assertThat(dist).isNotNull();
                    List<SettlementDto> settlements = queryService.settlements(m);
                    long sum = settlements.stream()
                            .flatMap(s -> s.records().stream())
                            .mapToLong(r -> r.amount()).sum();
                    assertThat(sum).isEqualTo(dist.totalAmount());
                    assertThat(detail.reportCount()).isGreaterThanOrEqualTo(1);
                }
                return null;
            }));
        }

        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        for (Future<?> f : futures) {
            f.get(); // 任何异常都会在这里抛出
        }

        // 最终一致性：每局 4 条领取记录，总额=发放额
        for (int i = 0; i < matchCount; i++) {
            String m = "cr-match-" + i;
            Long claims = jdbcTemplate.queryForObject(
                    "select count(*) from claims where match_id = ?", Long.class, m);
            Long total = jdbcTemplate.queryForObject(
                    "select total_amount from distributions where match_id = ?", Long.class, m);
            Long claimedSum = jdbcTemplate.queryForObject(
                    "select coalesce(sum(amount),0) from claims where match_id = ?", Long.class, m);
            assertThat(claims).isEqualTo(4L);
            assertThat(claimedSum).isEqualTo(total);
        }
    }

    @Test
    void concurrentDistribute_sameMatchExactlyOneDistribution() throws Exception {
        String m = "cd-match";
        oracleService.report(new ReportRequest(m, 1L, fourPlayers()));
        settlementService.settle(m);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<DistributeResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return distributionService.distribute(m);
            }));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        long uniqueIds = futures.stream().map(f -> {
            try { return f.get().distributionId(); } catch (Exception e) { throw new RuntimeException(e); }
        }).distinct().count();
        assertThat(uniqueIds).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from distributions where match_id = ?",
                Long.class, m)).isEqualTo(1L);
    }
}
