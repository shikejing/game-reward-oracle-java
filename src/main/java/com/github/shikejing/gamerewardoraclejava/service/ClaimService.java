package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.ApiException;
import com.github.shikejing.gamerewardoraclejava.common.ErrorCode;
import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
import com.github.shikejing.gamerewardoraclejava.model.Claim;
import com.github.shikejing.gamerewardoraclejava.model.Distribution;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.repository.ClaimRepository;
import com.github.shikejing.gamerewardoraclejava.repository.DistributionRepository;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ClaimResponse;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 领取服务。
 * <p>
 * 领取与结算、发放分离：仅已发放对局可领取；领取必须幂等——同一参与者重复领取
 * 只返回首次结果；参与者只能领取属于本人的奖励（无该记录则拒绝，错误码可区分）。
 * 领取事务不锁定对局行，慢领取不会阻塞新对局的结算推进。
 * <p>
 * 幂等插入使用 REQUIRES_NEW 独立事务：并发下唯一约束冲突只回滚嵌套事务，
 * 不影响外层事务，随后重查返回首次领取结果。
 */
@Service
public class ClaimService {

    private final MatchRepository matchRepository;
    private final DistributionRepository distributionRepository;
    private final RewardRecordRepository rewardRecordRepository;
    private final ClaimRepository claimRepository;
    private final EntityManager entityManager;
    private final ClaimInserter claimInserter;
    private final long slowClaimMs;

    public ClaimService(MatchRepository matchRepository,
                        DistributionRepository distributionRepository,
                        RewardRecordRepository rewardRecordRepository,
                        ClaimRepository claimRepository,
                        EntityManager entityManager,
                        ClaimInserter claimInserter,
                        @Value("${reward.test.slow-claim-ms:0}") long slowClaimMs) {
        this.matchRepository = matchRepository;
        this.distributionRepository = distributionRepository;
        this.rewardRecordRepository = rewardRecordRepository;
        this.claimRepository = claimRepository;
        this.entityManager = entityManager;
        this.claimInserter = claimInserter;
        this.slowClaimMs = slowClaimMs;
    }

    @Transactional
    public ClaimResponse claim(String matchId, ClaimRequest request) {
        if (request == null || request.playerId() == null || request.playerId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, "playerId 不能为空");
        }
        String playerId = request.playerId();

        Match match = matchRepository.findById(matchId)
                .orElseThrow(() -> new ApiException(ErrorCode.MATCH_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "对局不存在: " + matchId));

        Distribution distribution = distributionRepository.findByMatchId(matchId).orElse(null);
        if (distribution == null || match.getStatus() != MatchStatus.DISTRIBUTED) {
            throw new ApiException(ErrorCode.CLAIM_NOT_DISTRIBUTED, HttpStatus.CONFLICT,
                    "对局奖励尚未发放，不能领取: status=" + match.getStatus());
        }

        // 越权领取：该对局结算账目中无此参与者
        RewardRecord record = rewardRecordRepository
                .findBySettlementIdAndPlayerId(distribution.getSettlementId(), playerId)
                .orElseThrow(() -> new ApiException(ErrorCode.CLAIM_NOT_PARTICIPANT, HttpStatus.FORBIDDEN,
                        "参与者 " + playerId + " 无对局 " + matchId + " 的奖励记录，拒绝领取"));

        // 幂等：已有领取记录则返回首次结果
        Claim existing = claimRepository
                .findByDistributionIdAndPlayerId(distribution.getId(), playerId)
                .orElse(null);
        if (existing != null) {
            return toResponse(existing, true);
        }

        if (slowClaimMs > 0) {
            try {
                Thread.sleep(slowClaimMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Claim claim = new Claim();
        claim.setDistributionId(distribution.getId());
        claim.setMatchId(matchId);
        claim.setPlayerId(playerId);
        claim.setAmount(record.getAmount());
        claim.setClaimedAt(Instant.now());
        try {
            // REQUIRES_NEW：插入失败只回滚嵌套事务，外层事务不受影响
            claim = claimInserter.insert(claim);
        } catch (DataIntegrityViolationException e) {
            // 并发下同一参与者抢先领取：清空脏 session 后重查，返回首次结果
            entityManager.clear();
            Claim raced = claimRepository
                    .findByDistributionIdAndPlayerId(distribution.getId(), playerId)
                    .orElseThrow(() -> e);
            return toResponse(raced, true);
        }

        record.setStatus(RewardRecordStatus.CLAIMED);
        record.setClaimId(claim.getId());
        record.setClaimedAt(claim.getClaimedAt());
        rewardRecordRepository.save(record);

        return toResponse(claim, false);
    }

    private ClaimResponse toResponse(Claim c, boolean alreadyExisted) {
        return new ClaimResponse(c.getId(), c.getDistributionId(), c.getMatchId(),
                c.getPlayerId(), c.getAmount(), c.getClaimedAt(), alreadyExisted);
    }
}
