package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.ApiException;
import com.github.shikejing.gamerewardoraclejava.common.ErrorCode;
import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;
import com.github.shikejing.gamerewardoraclejava.common.SettlementStatus;
import com.github.shikejing.gamerewardoraclejava.config.RewardRuleProperties;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.model.RewardRecord;
import com.github.shikejing.gamerewardoraclejava.model.Settlement;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import com.github.shikejing.gamerewardoraclejava.repository.RewardRecordRepository;
import com.github.shikejing.gamerewardoraclejava.repository.SettlementRepository;
import com.github.shikejing.gamerewardoraclejava.web.dto.RewardRecordDto;
import com.github.shikejing.gamerewardoraclejava.web.dto.SettleResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 结算服务。
 * <p>
 * 确定性结算规则：
 * <ol>
 *   <li>参与者按得分降序、得分相同按 playerId 升序排列（仅用于确定并列组内顺序）；</li>
 *   <li>得分相同的参与者为并列名次，名次采用标准竞赛排名（1,2,2,4）；</li>
 *   <li>并列组的奖金池为该组所占各名次奖励之和，组内均分；除不尽时按 playerId 升序
 *       依次多发 1，保证金额为整数且结果确定；</li>
 *   <li>未配置名次的奖励取 defaultAmount。</li>
 * </ol>
 * 相同输入重复运行结果逐条一致；同一 (matchId, seq) 只结算一次，重复结算幂等返回。
 */
@Service
public class SettlementService {

    private final MatchRepository matchRepository;
    private final SettlementRepository settlementRepository;
    private final RewardRecordRepository rewardRecordRepository;
    private final RewardRuleProperties rule;

    public SettlementService(MatchRepository matchRepository,
                             SettlementRepository settlementRepository,
                             RewardRecordRepository rewardRecordRepository,
                             RewardRuleProperties rule) {
        this.matchRepository = matchRepository;
        this.settlementRepository = settlementRepository;
        this.rewardRecordRepository = rewardRecordRepository;
        this.rule = rule;
    }

    @Transactional
    public SettleResponse settle(String matchId) {
        Match match = matchRepository.findForUpdate(matchId)
                .orElseThrow(() -> new ApiException(ErrorCode.MATCH_NOT_FOUND, HttpStatus.NOT_FOUND,
                        "对局不存在: " + matchId));

        if (match.getStatus() == MatchStatus.DISTRIBUTED) {
            throw new ApiException(ErrorCode.SETTLE_ALREADY_DISTRIBUTED, HttpStatus.CONFLICT,
                    "对局已发放，奖励已发布，不可重新结算");
        }

        Settlement active = settlementRepository.findByMatchIdAndStatus(matchId, SettlementStatus.ACTIVE)
                .orElse(null);

        // 幂等：当前结论已结算
        if (active != null && active.getSeq() == match.getCurrentSeq()) {
            return toResponse(active);
        }

        if (match.getCurrentSeq() == 0 || match.getResultSnapshot() == null
                || match.getResultSnapshot().isEmpty()) {
            throw new ApiException(ErrorCode.SETTLE_NOT_REPORTED, HttpStatus.CONFLICT,
                    "对局尚无有效上报，无法结算");
        }

        // 修订：旧结算单及其账目标记为被取代（历史保留可查）
        if (active != null) {
            active.setStatus(SettlementStatus.SUPERSEDED);
            settlementRepository.save(active);
            List<RewardRecord> oldRecords =
                    rewardRecordRepository.findBySettlementIdOrderByRankAscPlayerIdAsc(active.getId());
            oldRecords.forEach(r -> r.setStatus(RewardRecordStatus.SUPERSEDED));
            rewardRecordRepository.saveAll(oldRecords);
        }

        List<RewardComputation> computed = computeRewards(match.getResultSnapshot(), rule);

        Settlement settlement = new Settlement();
        settlement.setMatchId(matchId);
        settlement.setSeq(match.getCurrentSeq());
        settlement.setStatus(SettlementStatus.ACTIVE);
        settlement.setSettledAt(Instant.now());
        settlement.setTotalAmount(computed.stream().mapToLong(c -> c.amount).sum());
        settlement = settlementRepository.save(settlement);

        List<RewardRecord> records = new ArrayList<>();
        for (RewardComputation c : computed) {
            RewardRecord r = new RewardRecord();
            r.setSettlementId(settlement.getId());
            r.setMatchId(matchId);
            r.setPlayerId(c.playerId);
            r.setRank(c.rank);
            r.setAmount(c.amount);
            r.setStatus(RewardRecordStatus.SETTLED);
            records.add(r);
        }
        rewardRecordRepository.saveAll(records);

        match.setStatus(MatchStatus.SETTLED);
        match.setSettledSeq(match.getCurrentSeq());
        match.setSettledAt(Instant.now());
        match.setRevisionDeadline(Instant.now().plusMillis(rule.windowMs()));
        matchRepository.save(match);

        return toResponse(settlement);
    }

    /**
     * 纯函数式结算：相同输入必得相同结果，供服务与单测复用。
     */
    static List<RewardComputation> computeRewards(List<PlayerResult> players, RewardRuleProperties rule) {
        List<PlayerResult> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparingLong(PlayerResult::score).reversed()
                .thenComparing(PlayerResult::playerId));

        Map<Integer, Long> rankRewards = rule.rankRewards() == null ? Map.of() : rule.rankRewards();
        long defaultAmount = rule.defaultAmount();

        List<RewardComputation> result = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j < sorted.size() && sorted.get(j).score() == sorted.get(i).score()) {
                j++;
            }
            int groupSize = j - i;
            int rank = i + 1; // 标准竞赛排名：并列者共享首名次 (1,2,2,4)

            long pool = 0;
            for (int pos = i + 1; pos <= j; pos++) {
                pool += rankRewards.getOrDefault(pos, defaultAmount);
            }
            long share = pool / groupSize;
            long remainder = pool % groupSize;

            // 组内按 playerId 升序，前 remainder 名多发 1，保证整数且确定
            List<PlayerResult> group = new ArrayList<>(sorted.subList(i, j));
            group.sort(Comparator.comparing(PlayerResult::playerId));
            for (int k = 0; k < group.size(); k++) {
                long amount = share + (k < remainder ? 1 : 0);
                result.add(new RewardComputation(group.get(k).playerId(), rank, amount));
            }
            i = j;
        }
        result.sort(Comparator.comparingInt((RewardComputation c) -> c.rank)
                .thenComparing(c -> c.playerId));
        return result;
    }

    private SettleResponse toResponse(Settlement settlement) {
        List<RewardRecord> records =
                rewardRecordRepository.findBySettlementIdOrderByRankAscPlayerIdAsc(settlement.getId());
        List<RewardRecordDto> dtos = records.stream()
                .map(r -> new RewardRecordDto(r.getPlayerId(), r.getRank(), r.getAmount(), r.getStatus()))
                .toList();
        return new SettleResponse(settlement.getMatchId(), settlement.getSeq(), settlement.getId(),
                settlement.getStatus().name(), settlement.getSettledAt(), settlement.getTotalAmount(), dtos);
    }

    /**
     * 结算计算结果（内部）。
     */
    record RewardComputation(String playerId, int rank, long amount) {
    }
}
