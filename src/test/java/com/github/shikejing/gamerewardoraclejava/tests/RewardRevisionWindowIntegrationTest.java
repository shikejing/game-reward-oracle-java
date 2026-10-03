package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;
import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 修订窗口边界行为测试。
 * 窗口规则：deadline = 首次结算时刻 + window-ms；窗口内（含边界）可修订；
 * 超出窗口拒绝修订；已发放拒绝修订；窗口关闭后结算仍最终有效且可发放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RewardRevisionWindowIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("reward").toAbsolutePath());
        r.add("reward.rule.window-ms", () -> "400");
        r.add("reward.rule.rank-rewards.1", () -> "100");
        r.add("reward.rule.rank-rewards.2", () -> "60");
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MatchRepository matchRepository;
    @Autowired SettlementRepository settlementRepository;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.execute("delete from claims");
        jdbcTemplate.execute("delete from reward_records");
        jdbcTemplate.execute("delete from distributions");
        jdbcTemplate.execute("delete from settlements");
        jdbcTemplate.execute("delete from match_reports");
        jdbcTemplate.execute("delete from matches");
    }

    private ReportResponse report(String m, long seq, List<PlayerResult> ps) {
        return rest.postForObject("/api/oracle/reports", new ReportRequest(m, seq, ps), ReportResponse.class);
    }

    private SettleResponse settle(String m) {
        return rest.postForObject("/api/matches/" + m + "/settle", null, SettleResponse.class);
    }

    @Test
    void revisionWithinWindow_thenRejectedAfterDeadline() throws Exception {
        String m = "win-match";
        report(m, 1, List.of(new PlayerResult("a", 100), new PlayerResult("b", 90)));
        SettleResponse s1 = settle(m);
        assertThat(s1.seq()).isEqualTo(1);

        // 窗口内立即修订 -> 接受
        ReportResponse rev = report(m, 2, List.of(new PlayerResult("a", 80), new PlayerResult("b", 90)));
        assertThat(rev.status()).isEqualTo(ReportStatus.ACCEPTED);
        SettleResponse s2 = settle(m);
        assertThat(s2.seq()).isEqualTo(2);

        // 等待超出窗口
        Thread.sleep(600);

        // 窗口外修订 -> 拒绝，不改写结论
        ReportResponse lateRev = report(m, 3, List.of(new PlayerResult("a", 0), new PlayerResult("b", 0)));
        assertThat(lateRev.status()).isEqualTo(ReportStatus.REVISION_CLOSED);
        assertThat(lateRev.reason()).contains("修订窗口已");

        // 结算幂等：不产生新结算单
        SettleResponse s3 = settle(m);
        assertThat(s3.settlementId()).isEqualTo(s2.settlementId());
        List<Settlement> settlements = settlementRepository.findByMatchIdOrderByIdDesc(m);
        assertThat(settlements).hasSize(2);
        assertThat(settlements.get(0).getStatus()).isEqualTo(SettlementStatus.ACTIVE);

        // 窗口关闭后仍可发放，且按窗口内最终结论
        rest.postForObject("/api/matches/" + m + "/distribute", null, Object.class);
        var match = matchRepository.findById(m).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(MatchStatus.DISTRIBUTED);

        // 已发放后修订 -> 拒绝
        ReportResponse afterDist = report(m, 4, List.of(new PlayerResult("a", 0), new PlayerResult("b", 0)));
        assertThat(afterDist.status()).isEqualTo(ReportStatus.ALREADY_DISTRIBUTED);
    }

    @Test
    void revisionBoundary_insideAccepted_outsideRejected() throws Exception {
        String m = "boundary-match";
        report(m, 1, List.of(new PlayerResult("a", 100)));
        settle(m);

        var match = matchRepository.findById(m).orElseThrow();
        long deadline = match.getRevisionDeadline().toEpochMilli();

        // 窗口内（截止前 60ms）上报 -> 接受
        long inside = deadline - System.currentTimeMillis() - 60;
        if (inside > 0) {
            Thread.sleep(inside);
        }
        ReportResponse insideRev = report(m, 2, List.of(new PlayerResult("a", 99)));
        assertThat(insideRev.status()).isEqualTo(ReportStatus.ACCEPTED);

        // 重新结算后窗口刷新，等待超出新窗口 -> 拒绝
        settle(m);
        var match2 = matchRepository.findById(m).orElseThrow();
        long outside = match2.getRevisionDeadline().toEpochMilli() - System.currentTimeMillis() + 150;
        if (outside > 0) {
            Thread.sleep(outside);
        }
        ReportResponse outsideRev = report(m, 3, List.of(new PlayerResult("a", 98)));
        assertThat(outsideRev.status()).isEqualTo(ReportStatus.REVISION_CLOSED);
    }
}
