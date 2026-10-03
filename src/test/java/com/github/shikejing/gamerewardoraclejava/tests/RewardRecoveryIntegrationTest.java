package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.Application;
import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;
import com.github.shikejing.gamerewardoraclejava.repository.ClaimRepository;
import com.github.shikejing.gamerewardoraclejava.repository.DistributionRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.service.ClaimService;
import com.github.shikejing.gamerewardoraclejava.service.DistributionService;
import com.github.shikejing.gamerewardoraclejava.service.OracleService;
import com.github.shikejing.gamerewardoraclejava.service.SettlementService;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重启恢复测试：
 * <ul>
 *   <li>已提交的结算、发放、领取在重启后完整还原；</li>
 *   <li>进行到一半的结算随事务回滚，不残留半份记录；</li>
 *   <li>恢复后重复领取幂等、重复上报不重复发放；</li>
 *   <li>恢复过程能区分已提交（DISTRIBUTED）与未完成（REPORTED）对局。</li>
 * </ul>
 */
class RewardRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private Properties props() {
        Properties p = new Properties();
        p.setProperty("spring.datasource.url", "jdbc:h2:file:" + tempDir.resolve("reward").toAbsolutePath());
        p.setProperty("reward.rule.window-ms", "60000");
        p.setProperty("reward.rule.rank-rewards.1", "100");
        p.setProperty("reward.rule.rank-rewards.2", "60");
        return p;
    }

    private ConfigurableApplicationContext start() {
        // 命令行参数优先级高于 application.properties，确保使用临时目录的文件 DB
        String[] args = props().entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        return new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Test
    void committedState_restoredAndIncompleteRolledBack() {
        // ---- 第一个进程：写入并提交 ----
        ConfigurableApplicationContext ctx1 = start();
        try {
            OracleService oracle = ctx1.getBean(OracleService.class);
            SettlementService settlement = ctx1.getBean(SettlementService.class);
            DistributionService distribution = ctx1.getBean(DistributionService.class);
            ClaimService claimService = ctx1.getBean(ClaimService.class);

            // m1：完整链路
            oracle.report(new ReportRequest("m1", 1L, List.of(
                    new PlayerResult("a", 100), new PlayerResult("b", 90))));
            settlement.settle("m1");
            distribution.distribute("m1");
            ClaimResponse claimedA = claimService.claim("m1", new ClaimRequest("a"));
            assertThat(claimedA.amount()).isEqualTo(100L);
            ClaimResponse claimedB = claimService.claim("m1", new ClaimRequest("b"));
            assertThat(claimedB.amount()).isEqualTo(60L);

            // m2：仅上报，未结算（未完成）
            oracle.report(new ReportRequest("m2", 1L, List.of(new PlayerResult("x", 50))));

            // m3：模拟结算中途崩溃（事务回滚）
            oracle.report(new ReportRequest("m3", 1L, List.of(new PlayerResult("a", 100))));
            CrashSimulator crash = ctx1.getBean(CrashSimulator.class);
            try {
                crash.crashAfterSettlement("m3");
            } catch (RuntimeException expected) {
                assertThat(expected.getMessage()).contains("simulated crash");
            }
        } finally {
            ctx1.close();
        }

        // ---- 第二个进程：恢复与核对 ----
        ConfigurableApplicationContext ctx2 = start();
        try {
            MatchRepository matches = ctx2.getBean(MatchRepository.class);
            SettlementRepository settlements = ctx2.getBean(SettlementRepository.class);
            RewardRecordRepository records = ctx2.getBean(RewardRecordRepository.class);
            DistributionRepository distributions = ctx2.getBean(DistributionRepository.class);
            ClaimRepository claims = ctx2.getBean(ClaimRepository.class);
            ClaimService claimService = ctx2.getBean(ClaimService.class);
            OracleService oracle = ctx2.getBean(OracleService.class);
            SettlementService settlement = ctx2.getBean(SettlementService.class);

            // 已提交对局完整还原
            var m1 = matches.findById("m1").orElseThrow();
            assertThat(m1.getStatus()).isEqualTo(MatchStatus.DISTRIBUTED);
            assertThat(m1.getDistributedAt()).isNotNull();
            assertThat(distributions.findByMatchId("m1")).isPresent();
            assertThat(claims.findByMatchIdOrderByClaimedAtAsc("m1")).hasSize(2);
            assertThat(records.findByMatchId("m1")).hasSize(2);

            // 未完成对局：仅 REPORTED，无结算残留
            var m2 = matches.findById("m2").orElseThrow();
            assertThat(m2.getStatus()).isEqualTo(MatchStatus.REPORTED);
            assertThat(settlements.findByMatchIdOrderByIdDesc("m2")).isEmpty();

            // 崩溃回滚：m3 无任何结算/账目残留
            var m3 = matches.findById("m3").orElseThrow();
            assertThat(m3.getStatus()).isEqualTo(MatchStatus.REPORTED);
            assertThat(settlements.findByMatchIdOrderByIdDesc("m3")).isEmpty();
            assertThat(records.findByMatchId("m3")).isEmpty();

            // 恢复后重复领取幂等：返回首次结果
            ClaimResponse again = claimService.claim("m1", new ClaimRequest("a"));
            assertThat(again.alreadyExisted()).isTrue();
            assertThat(again.amount()).isEqualTo(100L);
            assertThat(claims.findByMatchIdOrderByClaimedAtAsc("m1")).hasSize(2);

            // 恢复后重复上报不重复发放
            ReportResponse dup = oracle.report(new ReportRequest("m1", 1L, List.of(
                    new PlayerResult("a", 100), new PlayerResult("b", 90))));
            assertThat(dup.status()).isEqualTo(ReportStatus.DUPLICATE);

            // 已发放对局不可重新结算
            try {
                settlement.settle("m1");
            } catch (Exception e) {
                assertThat(e.getMessage()).contains("已发布");
            }

            // 账目总额自洽
            long claimTotal = claims.findByMatchIdOrderByClaimedAtAsc("m1").stream()
                    .mapToLong(c -> c.getAmount()).sum();
            long distTotal = distributions.findByMatchId("m1").orElseThrow().getTotalAmount();
            assertThat(claimTotal).isEqualTo(distTotal);
        } finally {
            ctx2.close();
        }
    }
}
