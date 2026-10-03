package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.ApiException;
import com.github.shikejing.gamerewardoraclejava.common.ErrorCode;
import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.model.Distribution;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.DistributionRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.DistributeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 发放服务：把已结算账目正式发布为可领取状态。
 * <p>
 * 发放记录一旦提交即持久化可查询，且每局唯一（uk_distribution_match），不可重复发放；
 * 重复调用幂等返回首次发放结果。发放后对局进入 DISTRIBUTED，奖励不可回退。
 */
@Service
public class DistributionService {

    private final MatchRepository matchRepository;
    private final DistributionRepository distributionRepository;
    private final SettlementRepository settlementRepository;
    private final RewardRecordRepository rewardRecordRepository;

    public DistributionService(MatchRepository matchRepository,
                               DistributionRepository distributionRepository,
                               SettlementRepository settlementRepository,
                               RewardRecordRepository rewardRecordRepository) {
        this.matchRepository = matchRepository;
        this.distributionRepository = distributionRepository;
        this.settlementRepository = settlementRepository;
        this.rewardRecordRepository = rewardRecordRepository;
    }

    @Transactional
    public DistributeResponse distribute(String matchId) {
        Match match = matchRepository.findForUpdate(matchId)
                .orElseThrow(() -> new ApiException(ErrorCode.MATCH_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "对局不存在: " + matchId));

        // 幂等：已发放则返回首次结果
        Distribution existing = distributionRepository.findByMatchId(matchId).orElse(null);
        if (existing != null) {
            return toResponse(existing, true);
        }

        if (match.getStatus() != MatchStatus.SETTLED && match.getStatus() != MatchStatus.REVISION_CLOSED) {
            throw new ApiException(ErrorCode.DISTRIBUTE_NOT_SETTLED, HttpStatus.CONFLICT,
                    "对局尚未结算，不能发放: status=" + match.getStatus());
        }

        Settlement active = settlementRepository.findByMatchIdAndStatus(matchId, SettlementStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ErrorCode.DISTRIBUTE_NOT_SETTLED, HttpStatus.CONFLICT,
                        "对局缺少生效结算单，不能发放"));

        List<RewardRecord> records =
                rewardRecordRepository.findBySettlementIdOrderByRankAscPlayerIdAsc(active.getId());
        if (records.isEmpty()) {
            throw new ApiException(ErrorCode.DISTRIBUTE_NOT_SETTLED, HttpStatus.CONFLICT,
                    "结算账目为空，不能发放");
        }

        long total = records.stream().mapToLong(RewardRecord::getAmount).sum();

        Distribution distribution = new Distribution();
        distribution.setMatchId(matchId);
        distribution.setSettlementId(active.getId());
        distribution.setDistributedAt(Instant.now());
        distribution.setTotalAmount(total);
        distribution.setRecordCount(records.size());
        distribution = distributionRepository.save(distribution);

        records.forEach(r -> {
            r.setStatus(RewardRecordStatus.DISTRIBUTED);
            r.setClaimId(null);
            r.setClaimedAt(null);
        });
        rewardRecordRepository.saveAll(records);

        active.setStatus(SettlementStatus.DISTRIBUTED);
        settlementRepository.save(active);

        match.setStatus(MatchStatus.DISTRIBUTED);
        match.setDistributedAt(Instant.now());
        matchRepository.save(match);

        return toResponse(distribution, false);
    }

    private DistributeResponse toResponse(Distribution d, boolean alreadyExisted) {
        return new DistributeResponse(d.getId(), d.getMatchId(), d.getSettlementId(),
                d.getDistributedAt(), d.getTotalAmount(), d.getRecordCount(), alreadyExisted);
    }
}
