package com.github.shikejing.reward.api;

import com.github.shikejing.reward.domain.Model.*;
import com.github.shikejing.reward.service.*;
import com.github.shikejing.reward.service.ClaimService.ClaimResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** REST 入口：上报、修订、结算、发放、领取、查询。 */
@RestController
@RequestMapping("/api/matches")
public class RewardController {

    private final MatchService matchService;
    private final SettlementService settlementService;
    private final ClaimService claimService;

    public RewardController(MatchService matchService, SettlementService settlementService,
                            ClaimService claimService) {
        this.matchService = matchService;
        this.settlementService = settlementService;
        this.claimService = claimService;
    }

    public record ReportRequest(long reportSeq, List<PlayerScore> scores) {}
    public record ClaimRequest(String playerId, String requesterId) {}

    @PostMapping("/{matchId}/reports")
    public MatchReport report(@PathVariable String matchId, @RequestBody ReportRequest req) {
        return matchService.submitReport(matchId, req.reportSeq(), req.scores());
    }

    @PostMapping("/{matchId}/reports/{seq}/revoke")
    public Map<String, Object> revoke(@PathVariable String matchId, @PathVariable long seq) {
        matchService.revokeReport(matchId, seq);
        return Map.of("matchId", matchId, "revokedSeq", seq);
    }

    @GetMapping("/{matchId}/conclusion")
    public MatchReport conclusion(@PathVariable String matchId) {
        return matchService.conclusionOf(matchId);
    }

    @GetMapping("/{matchId}/reports")
    public List<MatchReport> history(@PathVariable String matchId) {
        return matchService.historyOf(matchId);
    }

    @PostMapping("/{matchId}/settle")
    public Settlement settle(@PathVariable String matchId) {
        return settlementService.settle(matchId);
    }

    @PostMapping("/{matchId}/grant")
    public List<GrantRecord> grant(@PathVariable String matchId) {
        return settlementService.grant(matchId);
    }

    @GetMapping("/{matchId}/grants")
    public List<GrantRecord> grants(@PathVariable String matchId) {
        return settlementService.grant(matchId); // 幂等，查询不触发重复发放
    }

    @PostMapping("/{matchId}/claims")
    public ResponseEntity<?> claim(@PathVariable String matchId, @RequestBody ClaimRequest req) {
        ClaimResult r = claimService.claim(matchId, req.playerId(), req.requesterId());
        if (r.ok()) return ResponseEntity.ok(r.record());
        HttpStatus status = switch (r.failure()) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, NOT_GRANTED -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status)
                .body(Map.of("failure", r.failure().name()));
    }

    @GetMapping("/{matchId}/claims/{playerId}")
    public ResponseEntity<?> claimOf(@PathVariable String matchId, @PathVariable String playerId) {
        return claimService.claimOf(matchId, playerId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<?> onServiceError(ServiceException e) {
        HttpStatus status = switch (e.reason()) {
            case MATCH_NOT_FOUND, REPORT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_REPORT_CONFLICT -> HttpStatus.CONFLICT;
            case REVISION_WINDOW_CLOSED, ALREADY_SETTLED, NOT_SETTLED, ALREADY_GRANTED ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status)
                .body(Map.of("reason", e.reason().name(), "message", e.getMessage()));
    }
}
