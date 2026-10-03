package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.service.ClaimService;
import com.github.shikejing.gamerewardoraclejava.service.DistributionService;
import com.github.shikejing.gamerewardoraclejava.service.OracleService;
import com.github.shikejing.gamerewardoraclejava.service.SettlementService;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 慢领取测试：领取事务不锁定对局行，慢领取不得阻塞新对局的结算推进。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "reward.test.slow-claim-ms=400")
class RewardSlowClaimIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("reward").toAbsolutePath());
        r.add("reward.rule.window-ms", () -> "60000");
        r.add("reward.rule.rank-rewards.1", () -> "100");
    }

    @Autowired OracleService oracleService;
    @Autowired SettlementService settlementService;
    @Autowired DistributionService distributionService;
    @Autowired ClaimService claimService;
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

    @Test
    void slowClaim_doesNotBlockOtherMatchSettlement() throws Exception {
        // 对局 A 已发放，参与者 a 发起慢领取（事务内停留 400ms）
        String a = "slow-match";
        oracleService.report(new ReportRequest(a, 1L, List.of(new PlayerResult("a", 100))));
        settlementService.settle(a);
        distributionService.distribute(a);

        Future<ClaimResponse> slowClaim = java.util.concurrent.Executors.newSingleThreadExecutor()
                .submit(() -> claimService.claim(a, new ClaimRequest("a")));

        // 对局 B 同时进行结算+发放，必须在慢领取期间快速完成
        String b = "fast-match";
        long start = System.currentTimeMillis();
        oracleService.report(new ReportRequest(b, 1L, List.of(new PlayerResult("b", 100))));
        settlementService.settle(b);
        distributionService.distribute(b);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(3000);
        assertThat(jdbcTemplate.queryForObject("select count(*) from distributions where match_id = ?",
                Long.class, b)).isEqualTo(1L);

        // 慢领取本身仍正确完成
        ClaimResponse resp = slowClaim.get(10, TimeUnit.SECONDS);
        assertThat(resp.amount()).isEqualTo(100L);
        assertThat(resp.alreadyExisted()).isFalse();
    }
}
