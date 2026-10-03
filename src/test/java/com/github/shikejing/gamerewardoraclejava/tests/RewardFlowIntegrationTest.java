package com.github.shikejing.gamerewardoraclejava.tests;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;
import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.model.Claim;
import com.github.shikejing.gamerewardoraclejava.model.Distribution;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.ClaimRepository;
import com.github.shikejing.gamerewardoraclejava.repository.DistributionRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchReportRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributeResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.ErrorResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端集成测试：上报收敛、并列结算、结算前修订、发放与领取幂等、越权拒绝、全链路追溯。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RewardFlowIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("reward").toAbsolutePath());
        r.add("reward.rule.window-ms", () -> "60000");
        r.add("reward.rule.rank-rewards.1", () -> "100");
        r.add("reward.rule.rank-rewards.2", () -> "60");
        r.add("reward.rule.rank-rewards.3", () -> "30");
    }

    @Autowired TestRestTemplate rest;
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
    @Autowired MatchRepository matchRepository;
    @Autowired MatchReportRepository reportRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired RewardRecordRepository rewardRecordRepository;
    @Autowired DistributionRepository distributionRepository;
    @Autowired ClaimRepository claimRepository;

    private static List<PlayerResult> players(Object... kv) {
        // kv: playerId, score, ...
        var b = new java.util.ArrayList<PlayerResult>();
        for (int i = 0; i < kv.length; i += 2) {
            b.add(new PlayerResult((String) kv[i], (Long) kv[i + 1]));
        }
        return b;
    }

    private ReportResponse report(String matchId, long seq, List<PlayerResult> ps) {
        return rest.postForObject("/api/oracle/reports", new ReportRequest(matchId, seq, ps),
                ReportResponse.class);
    }

    private SettleResponse settle(String matchId) {
        return rest.postForObject("/api/matches/" + matchId + "/settle", null, SettleResponse.class);
    }

    private DistributeResponse distribute(String matchId) {
        return rest.postForObject("/api/matches/" + matchId + "/distribute", null, DistributeResponse.class);
    }

    private ClaimResponse claim(String matchId, String playerId) {
        return rest.postForObject("/api/matches/" + matchId + "/claim", new ClaimRequest(playerId),
                ClaimResponse.class);
    }

    @Test
    void reportConvergence_duplicateOutOfOrderAndLate_noDoubleReward() {
        String m = "conv-match";
        ReportResponse r1 = report(m, 1, players("a", 100L, "b", 90L));
        ReportResponse dup = report(m, 1, players("a", 100L, "b", 90L));
        ReportResponse skip = report(m, 3, players("a", 80L, "b", 90L));
        ReportResponse late = report(m, 2, players("a", 100L, "b", 90L));

        assertThat(dup.status()).isEqualTo(ReportStatus.DUPLICATE);
        assertThat(skip.status()).isEqualTo(ReportStatus.ACCEPTED);
        assertThat(late.status()).isEqualTo(ReportStatus.LATE);

        // 结算以最大序号 3 的结论为准
        SettleResponse s = settle(m);
        assertThat(s.seq()).isEqualTo(3);
        assertThat(s.records()).extracting(r -> r.playerId()).containsExactly("b", "a");

        // 重复结算幂等
        SettleResponse s2 = settle(m);
        assertThat(s2.settlementId()).isEqualTo(s.settlementId());

        distribute(m);
        ClaimResponse ca = claim(m, "a");
        ClaimResponse cb = claim(m, "b");
        assertThat(ca.amount()).isEqualTo(60); // seq3: a 80 分第 2 名
        assertThat(cb.amount()).isEqualTo(100);

        // 重复领取幂等：返回首次结果
        ClaimResponse caAgain = claim(m, "a");
        assertThat(caAgain.alreadyExisted()).isTrue();
        assertThat(caAgain.claimId()).isEqualTo(ca.claimId());
        assertThat(caAgain.amount()).isEqualTo(ca.amount());

        // 账目核对：每参与者恰好一条奖励、一条领取，总额=发放额
        List<RewardRecord> records = rewardRecordRepository.findByMatchId(m);
        List<Claim> claims = claimRepository.findByMatchIdOrderByClaimedAtAsc(m);
        Distribution d = distributionRepository.findByMatchId(m).orElseThrow();
        assertThat(records).hasSize(2);
        assertThat(claims).hasSize(2);
        assertThat(claims).extracting(Claim::getAmount).containsExactly(60L, 100L);
        assertThat(d.getTotalAmount()).isEqualTo(160L);
        assertThat(records.stream().mapToLong(RewardRecord::getAmount).sum()).isEqualTo(d.getTotalAmount());
    }

    @Test
    void tieRanks_splitPoolDeterministically() {
        String m = "tie-match";
        report(m, 1, players("a", 100L, "b", 100L, "c", 90L));
        SettleResponse s = settle(m);

        // a/b 并列第 1：池 100+60=160 均分 80；c 第 3 名 30
        assertThat(s.records()).containsExactly(
                new com.github.shikejing.gamerewardoraclejava.web.dto.RewardRecordDto("a", 1, 80,
                        RewardRecordStatus.SETTLED),
                new com.github.shikejing.gamerewardoraclejava.web.dto.RewardRecordDto("b", 1, 80,
                        RewardRecordStatus.SETTLED),
                new com.github.shikejing.gamerewardoraclejava.web.dto.RewardRecordDto("c", 3, 30,
                        RewardRecordStatus.SETTLED));

        distribute(m);
        assertThat(claim(m, "a").amount()).isEqualTo(80);
        assertThat(claim(m, "b").amount()).isEqualTo(80);
        assertThat(claim(m, "c").amount()).isEqualTo(30);
    }

    @Test
    void revision_withinWindow_replacesSettlementAndOldIsSuperseded() {
        String m = "rev-match";
        report(m, 1, players("a", 100L, "b", 90L));
        SettleResponse s1 = settle(m);
        assertThat(s1.seq()).isEqualTo(1);

        // 窗口内撤销并替换
        ReportResponse rev = report(m, 2, players("a", 80L, "b", 90L));
        assertThat(rev.status()).isEqualTo(ReportStatus.ACCEPTED);

        SettleResponse s2 = settle(m);
        assertThat(s2.seq()).isEqualTo(2);
        // 新结论：b 第 1 名 100，a 第 2 名 60
        assertThat(s2.records()).extracting(r -> r.playerId()).containsExactly("b", "a");
        assertThat(s2.records()).extracting(r -> r.amount()).containsExactly(100L, 60L);

        // 结算历史：旧单被取代，新单生效
        List<Settlement> settlements = settlementRepository.findByMatchIdOrderByIdDesc(m);
        assertThat(settlements).hasSize(2);
        assertThat(settlements.get(0).getStatus()).isEqualTo(SettlementStatus.ACTIVE);
        assertThat(settlements.get(0).getSeq()).isEqualTo(2L);
        assertThat(settlements.get(1).getStatus()).isEqualTo(SettlementStatus.SUPERSEDED);
        List<RewardRecord> oldRecords =
                rewardRecordRepository.findBySettlementIdOrderByRankAscPlayerIdAsc(settlements.get(1).getId());
        assertThat(oldRecords).allMatch(r -> r.getStatus() == RewardRecordStatus.SUPERSEDED);

        distribute(m);
        Distribution d = distributionRepository.findByMatchId(m).orElseThrow();
        assertThat(d.getSettlementId()).isEqualTo(s2.settlementId());

        // 修订后发放，按新结果领取，旧账目不参与
        assertThat(claim(m, "a").amount()).isEqualTo(60);
        assertThat(claim(m, "b").amount()).isEqualTo(100);
        assertThat(claimRepository.findByMatchIdOrderByClaimedAtAsc(m)).hasSize(2);
    }

    @Test
    void unauthorizedClaim_rejectedWithDistinguishableReason() {
        String m = "auth-match";
        report(m, 1, players("a", 100L));
        settle(m);
        distribute(m);

        // 非参与者越权领取 -> 403 CLAIM_NOT_PARTICIPANT
        ResponseEntity<ErrorResponse> forbidden = rest.exchange(
                "/api/matches/" + m + "/claim", HttpMethod.POST,
                new HttpEntity<>(new ClaimRequest("outsider")), ErrorResponse.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().errorCode()).isEqualTo("CLAIM_NOT_PARTICIPANT");

        // 未发放对局领取 -> 409 CLAIM_NOT_DISTRIBUTED
        String m2 = "auth-match-2";
        report(m2, 1, players("x", 100L));
        ResponseEntity<ErrorResponse> notDistributed = rest.exchange(
                "/api/matches/" + m2 + "/claim", HttpMethod.POST,
                new HttpEntity<>(new ClaimRequest("x")), ErrorResponse.class);
        assertThat(notDistributed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notDistributed.getBody().errorCode()).isEqualTo("CLAIM_NOT_DISTRIBUTED");

        // 越权领取不产生任何账目
        assertThat(claimRepository.findByMatchIdOrderByClaimedAtAsc(m)).isEmpty();
    }

    @Test
    void distribute_isIdempotent() {
        String m = "dist-match";
        report(m, 1, players("a", 100L));
        settle(m);
        DistributeResponse d1 = distribute(m);
        DistributeResponse d2 = distribute(m);
        assertThat(d2.alreadyExisted()).isTrue();
        assertThat(d2.distributionId()).isEqualTo(d1.distributionId());
        assertThat(distributionRepository.findByMatchId(m)).isPresent();
        assertThat(distributionRepository.count()).isEqualTo(1L);
    }

    @Test
    void settle_withoutReport_rejected() {
        ResponseEntity<ErrorResponse> resp = rest.postForEntity(
                "/api/matches/empty-match/settle", null, ErrorResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().errorCode()).isEqualTo("MATCH_NOT_FOUND");
    }

    @Test
    void traceability_fullChainQueryable() {
        String m = "trace-match";
        report(m, 1, players("a", 100L, "b", 90L));
        settle(m);
        distribute(m);
        claim(m, "a");

        ResponseEntity<Map> detail = rest.getForEntity("/api/matches/" + m, Map.class);
        assertThat(detail.getBody().get("status")).isEqualTo("DISTRIBUTED");
        assertThat(detail.getBody().get("reportCount")).isEqualTo(1);
        assertThat(detail.getBody().get("settlementCount")).isEqualTo(1);

        List<Map> reports = rest.exchange("/api/matches/" + m + "/reports", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map>>() {}).getBody();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).get("status")).isEqualTo("ACCEPTED");

        List<Map> settlements = rest.exchange("/api/matches/" + m + "/settlements", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map>>() {}).getBody();
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).get("status")).isEqualTo("DISTRIBUTED");

        Map distribution = rest.getForObject("/api/matches/" + m + "/distribution", Map.class);
        assertThat(distribution.get("totalAmount")).isEqualTo(160);

        List<Map> claims = rest.exchange("/api/matches/" + m + "/claims", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map>>() {}).getBody();
        assertThat(claims).hasSize(1);

        List<Map> playerClaims = rest.exchange("/api/players/a/claims", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<Map>>() {}).getBody();
        assertThat(playerClaims).hasSize(1);

        // 数据库侧状态一致
        Match match = matchRepository.findById(m).orElseThrow();
        assertThat(match.getStatus()).isEqualTo(MatchStatus.DISTRIBUTED);
        assertThat(match.getDistributedAt()).isNotNull();
    }
}
