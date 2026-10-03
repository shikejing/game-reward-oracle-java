package com.github.shikejing.reward.service;

import com.github.shikejing.reward.config.RewardRuleConfig;
import com.github.shikejing.reward.domain.Model.*;
import com.github.shikejing.reward.service.ServiceException.Reason;
import com.github.shikejing.reward.store.JournalStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 确定性结算与发放。
 * <ul>
 *   <li>名次：分数降序；同分并列同一名次（竞赛排名 1,2,2,4），
 *       展示顺序按 playerId 字典序，保证逐条可复现；</li>
 *   <li>并列处理：EACH_FULL 每人拿该名次全额；
 *       SPLIT_SUM 合并并列组占用的所有名次额度后均分，
 *       余数按 playerId 字典序前 rem 名各 +1（确定性分配，总额守恒）；</li>
 *   <li>settle/grant 均幂等：已提交则直接返回已提交版本，绝不二次结算或重复发放。</li>
 * </ul>
 */
@Service
public class SettlementService {

    private final JournalStore store;
    private final MatchService matchService;
    private final RewardRuleConfig rule;
    private final Clock clock;

    public SettlementService(JournalStore store, MatchService matchService,
                             RewardRuleConfig rule, Clock clock) {
        this.store = store;
        this.matchService = matchService;
        this.rule = rule;
        this.clock = clock;
    }

    /** 结算：同一对局只结算一次，重复调用返回首次已提交结果。 */
    public Settlement settle(String matchId) {
        var lock = matchService.lockOf(matchId);
        lock.lock();
        try {
            var existing = store.settlementOf(matchId);
            if (existing.isPresent()) return existing.get(); // 幂等
            MatchReport conclusion = matchService.conclusionOf(matchId);
            Settlement settlement = new Settlement(matchId, conclusion.reportSeq(),
                    computeLines(conclusion.scores()), clock.millis());
            store.commitSettlement(settlement); // 单条事件原子落盘
            return settlement;
        } finally {
            lock.unlock();
        }
    }

    /** 发放：基于已提交结算生成发放记录，幂等。 */
    public List<GrantRecord> grant(String matchId) {
        var lock = matchService.lockOf(matchId);
        lock.lock();
        try {
            Settlement settlement = store.settlementOf(matchId)
                    .orElseThrow(() -> new ServiceException(Reason.NOT_SETTLED,
                            "尚未结算，不能发放: " + matchId));
            List<GrantRecord> existing = store.grantsOf(matchId);
            if (!existing.isEmpty()) return existing; // 幂等：不重复发放
            List<GrantRecord> grants = new ArrayList<>();
            long now = clock.millis();
            for (RewardLine line : settlement.lines()) {
                grants.add(new GrantRecord(matchId, line.playerId(), line.amount(), now));
            }
            store.commitGrants(matchId, grants); // 单条事件整体提交
            return grants;
        } finally {
            lock.unlock();
        }
    }

    /** 纯函数预览：相同输入必得相同输出，用于复现性验证。 */
    public List<RewardLine> preview(List<PlayerScore> scores) {
        return computeLines(scores);
    }

    /** 确定性结算核心：排序 -> 竞赛排名 -> 并列分配。 */
    List<RewardLine> computeLines(List<PlayerScore> scores) {
        List<PlayerScore> sorted = scores.stream()
                .sorted(Comparator.comparingLong(PlayerScore::score).reversed()
                        .thenComparing(PlayerScore::playerId))
                .toList();
        List<RewardLine> lines = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int rank = i + 1; // 竞赛排名：并列组共享起始名次
            int j = i;
            while (j < sorted.size() && sorted.get(j).score() == sorted.get(i).score()) j++;
            List<PlayerScore> tied = sorted.subList(i, j);
            if (rule.getTiePolicy() == TiePolicy.EACH_FULL) {
                long amount = amountOf(rank);
                for (PlayerScore p : tied) {
                    lines.add(new RewardLine(p.playerId(), rank, amount));
                }
            } else { // SPLIT_SUM
                long sum = 0;
                for (int r = rank; r < rank + tied.size(); r++) sum += amountOf(r);
                long share = sum / tied.size();
                long rem = sum % tied.size();
                for (int k = 0; k < tied.size(); k++) {
                    // 余数按 playerId 序（sorted 已按 playerId 排好）前 rem 名各 +1
                    lines.add(new RewardLine(tied.get(k).playerId(), rank,
                            share + (k < rem ? 1 : 0)));
                }
            }
            i = j;
        }
        return lines;
    }

    private long amountOf(int rank) {
        return rule.getRankAmounts().getOrDefault(rank, 0L);
    }
}
