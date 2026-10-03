package com.github.shikejing.gamerewardoraclejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 奖励规则配置。
 *
 * @param windowMs       结算后的修订窗口时长（毫秒）
 * @param defaultAmount  未配置名次的默认奖励
 * @param rankRewards    名次 -> 奖励额度（名次从 1 开始）
 */
@ConfigurationProperties(prefix = "reward.rule")
public record RewardRuleProperties(long windowMs,
                                   long defaultAmount,
                                   Map<Integer, Long> rankRewards) {
}
