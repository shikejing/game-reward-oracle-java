package com.github.shikejing.gamerewardoraclejava.web;

import com.github.shikejing.gamerewardoraclejava.service.ClaimService;
import com.github.shikejing.gamerewardoraclejava.service.DistributionService;
import com.github.shikejing.gamerewardoraclejava.service.MatchQueryService;
import com.github.shikejing.gamerewardoraclejava.service.SettlementService;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributionDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributeResponse;
import com.github.shikejing.gamerewardoraclejava.web.dto.MatchDetail;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportHistoryDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettlementDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettleResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对局结算、发放、领取与查询入口。
 */
@RestController
@RequestMapping("/api")
public class MatchController {

    private final SettlementService settlementService;
    private final DistributionService distributionService;
    private final ClaimService claimService;
    private final MatchQueryService queryService;

    public MatchController(SettlementService settlementService,
                           DistributionService distributionService,
                           ClaimService claimService,
                           MatchQueryService queryService) {
        this.settlementService = settlementService;
        this.distributionService = distributionService;
        this.claimService = claimService;
        this.queryService = queryService;
    }

    @PostMapping("/matches/{matchId}/settle")
    public SettleResponse settle(@PathVariable String matchId) {
        return settlementService.settle(matchId);
    }

    @PostMapping("/matches/{matchId}/distribute")
    public DistributeResponse distribute(@PathVariable String matchId) {
        return distributionService.distribute(matchId);
    }

    @PostMapping("/matches/{matchId}/claim")
    public ClaimResponse claim(@PathVariable String matchId, @RequestBody ClaimRequest request) {
        return claimService.claim(matchId, request);
    }

    @GetMapping("/matches/{matchId}")
    public MatchDetail detail(@PathVariable String matchId) {
        return queryService.detail(matchId);
    }

    @GetMapping("/matches/{matchId}/reports")
    public List<ReportHistoryDto> reports(@PathVariable String matchId) {
        return queryService.reports(matchId);
    }

    @GetMapping("/matches/{matchId}/settlements")
    public List<SettlementDto> settlements(@PathVariable String matchId) {
        return queryService.settlements(matchId);
    }

    @GetMapping("/matches/{matchId}/distribution")
    public DistributionDto distribution(@PathVariable String matchId) {
        return queryService.distribution(matchId);
    }

    @GetMapping("/matches/{matchId}/claims")
    public List<ClaimDto> claims(@PathVariable String matchId) {
        return queryService.claims(matchId);
    }

    @GetMapping("/players/{playerId}/claims")
    public List<ClaimDto> playerClaims(@PathVariable String playerId) {
        return queryService.playerClaims(playerId);
    }
}
