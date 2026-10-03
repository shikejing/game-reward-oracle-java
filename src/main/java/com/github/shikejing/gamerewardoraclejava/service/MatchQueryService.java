package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.ApiException;
import com.github.shikejing.gamerewardoraclejava.common.ErrorCode;
import com.github.shikejing.gamerewardoraclejava.model.Claim;
import com.github.shikejing.gamerewardoraclejava.model.Distribution;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.MatchReport;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.ClaimRepository;
import com.github.shikejing.gamerewardoraclejava.repository.DistributionRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchReportRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributionDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.MatchDetail;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportHistoryDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.RewardRecordDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettlementDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 查询服务：对局全链路追溯（上报历史、结算历史、发放、领取）。
 */
@Service
@Transactional(readOnly = true)
public class MatchQueryService {

    private final MatchRepository matchRepository;
    private final MatchReportRepository reportRepository;
    private final SettlementRepository settlementRepository;
    private final RewardRecordRepository rewardRecordRepository;
    private final DistributionRepository distributionRepository;
    private final ClaimRepository claimRepository;

    public MatchQueryService(MatchRepository matchRepository,
                             MatchReportRepository reportRepository,
                             SettlementRepository settlementRepository,
                             RewardRecordRepository rewardRecordRepository,
                             DistributionRepository distributionRepository,
                             ClaimRepository claimRepository) {
        this.matchRepository = matchRepository;
        this.reportRepository = reportRepository;
        this.settlementRepository = settlementRepository;
        this.rewardRecordRepository = rewardRecordRepository;
        this.distributionRepository = distributionRepository;
        this.claimRepository = claimRepository;
    }

    public MatchDetail detail(String matchId) {
        Match match = mustFindMatch(matchId);
        long reports = reportRepository.findByMatchIdOrderBySeqDesc(matchId).size();
        long settlements = settlementRepository.findByMatchIdOrderByIdDesc(matchId).size();
        return new MatchDetail(match.getMatchId(), match.getStatus(), match.getCurrentSeq(),
                match.getSettledSeq(), match.getSettledAt(), match.getRevisionDeadline(),
                match.getDistributedAt(), match.getResultSnapshot(), reports, settlements);
    }

    public List<ReportHistoryDto> reports(String matchId) {
        mustFindMatch(matchId);
        return reportRepository.findByMatchIdOrderBySeqDesc(matchId).stream()
                .map(r -> new ReportHistoryDto(r.getId(), r.getSeq(), r.getStatus(), r.getReason(),
                        r.getReportedAt(), r.getPlayers()))
                .toList();
    }

    public List<SettlementDto> settlements(String matchId) {
        mustFindMatch(matchId);
        return settlementRepository.findByMatchIdOrderByIdDesc(matchId).stream()
                .map(s -> new SettlementDto(s.getId(), s.getSeq(), s.getStatus(), s.getSettledAt(),
                        s.getTotalAmount(), recordDtos(s.getId())))
                .toList();
    }

    public DistributionDto distribution(String matchId) {
        mustFindMatch(matchId);
        Distribution d = distributionRepository.findByMatchId(matchId).orElse(null);
        if (d == null) {
            return null;
        }
        return new DistributionDto(d.getId(), d.getMatchId(), d.getSettlementId(),
                d.getDistributedAt(), d.getTotalAmount(), d.getRecordCount());
    }

    public List<ClaimDto> claims(String matchId) {
        mustFindMatch(matchId);
        return claimRepository.findByMatchIdOrderByClaimedAtAsc(matchId).stream()
                .map(MatchQueryService::toClaimDto)
                .toList();
    }

    public List<ClaimDto> playerClaims(String playerId) {
        return claimRepository.findByPlayerIdOrderByClaimedAtAsc(playerId).stream()
                .map(MatchQueryService::toClaimDto)
                .toList();
    }

    private List<RewardRecordDto> recordDtos(Long settlementId) {
        return rewardRecordRepository.findBySettlementIdOrderByRankAscPlayerIdAsc(settlementId).stream()
                .map(r -> new RewardRecordDto(r.getPlayerId(), r.getRank(), r.getAmount(), r.getStatus()))
                .toList();
    }

    private static ClaimDto toClaimDto(Claim c) {
        return new ClaimDto(c.getId(), c.getDistributionId(), c.getMatchId(), c.getPlayerId(),
                c.getAmount(), c.getClaimedAt());
    }

    private Match mustFindMatch(String matchId) {
        return matchRepository.findById(matchId)
                .orElseThrow(() -> new ApiException(ErrorCode.MATCH_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "对局不存在: " + matchId));
    }
}
