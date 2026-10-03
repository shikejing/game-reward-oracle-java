package com.github.shikejing.reward.config;

import com.github.shikejing.reward.domain.Model.TiePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 奖励规则配置：名次 -> 额度，并列策略，修订窗口。 */
@Component
@ConfigurationProperties(prefix = "reward")
public class RewardRuleConfig {
    /** 名次(从1开始) -> 奖励额度；未配置的名次额度为0。 */
    private Map<Integer, Long> rankAmounts = new LinkedHashMap<>(Map.of(1, 100L, 2, 50L, 3, 20L));
    /** 并列名次处理：EACH_FULL 每人全额；SPLIT_SUM 合并被占用名次的额度后平分。 */
    private TiePolicy tiePolicy = TiePolicy.SPLIT_SUM;
    /** 结算前修订窗口（毫秒），自首次上报收到时刻起算。 */
    private long revisionWindowMs = 60_000L;

    public Map<Integer, Long> getRankAmounts() { return rankAmounts; }
    public void setRankAmounts(Map<Integer, Long> rankAmounts) { this.rankAmounts = rankAmounts; }
    public TiePolicy getTiePolicy() { return tiePolicy; }
    public void setTiePolicy(TiePolicy tiePolicy) { this.tiePolicy = tiePolicy; }
    public long getRevisionWindowMs() { return revisionWindowMs; }
    public void setRevisionWindowMs(long revisionWindowMs) { this.revisionWindowMs = revisionWindowMs; }
}
