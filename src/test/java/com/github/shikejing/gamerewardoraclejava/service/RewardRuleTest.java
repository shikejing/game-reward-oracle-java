package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.config.RewardRuleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算规则纯函数测试：排名、并列处理、奖金拆分与确定性。
 */
class RewardRuleTest {

    private final RewardRuleProperties rule =
            new RewardRuleProperties(60_000L, 0L, Map.of(1, 100L, 2, 60L, 3, 30L));

    @Test
    void noTies_ranksAndRewards() {
        List<PlayerResult> players = List.of(
                new PlayerResult("a", 100),
                new PlayerResult("b", 90),
                new PlayerResult("c", 80),
                new PlayerResult("d", 70));

        List<SettlementService.RewardComputation> r = SettlementService.computeRewards(players, rule);

        assertThat(r).containsExactly(
                new SettlementService.RewardComputation("a", 1, 100),
                new SettlementService.RewardComputation("b", 2, 60),
                new SettlementService.RewardComputation("c", 3, 30),
                new SettlementService.RewardComputation("d", 4, 0)); // 未配置名次 -> 默认 0
    }

    @Test
    void tieForFirst_splitsPool() {
        List<PlayerResult> players = List.of(
                new PlayerResult("a", 100),
                new PlayerResult("b", 100),
                new PlayerResult("c", 90));

        List<SettlementService.RewardComputation> r = SettlementService.computeRewards(players, rule);

        // 并列第 1：占据名次 1、2，奖金池 100+60=160，均分 80/80；c 为第 3 名 30
        assertThat(r).containsExactly(
                new SettlementService.RewardComputation("a", 1, 80),
                new SettlementService.RewardComputation("b", 1, 80),
                new SettlementService.RewardComputation("c", 3, 30));
    }

    @Test
    void threeWayTie_remainderGoesToLexicographicallyFirst() {
        List<PlayerResult> players = List.of(
                new PlayerResult("c", 50),
                new PlayerResult("a", 50),
                new PlayerResult("b", 50));

        List<SettlementService.RewardComputation> r = SettlementService.computeRewards(players, rule);

        // 三人并列第 1：池 100+60+30=190，190/3=63 余 1；按 playerId 升序 a 多得 1 -> 64/63/63
        assertThat(r).containsExactly(
                new SettlementService.RewardComputation("a", 1, 64),
                new SettlementService.RewardComputation("b", 1, 63),
                new SettlementService.RewardComputation("c", 1, 63));
    }

    @Test
    void deterministic_sameInputSameOutput() {
        List<PlayerResult> players = List.of(
                new PlayerResult("z", 80),
                new PlayerResult("a", 90),
                new PlayerResult("m", 90),
                new PlayerResult("b", 70));

        List<SettlementService.RewardComputation> r1 = SettlementService.computeRewards(players, rule);
        List<SettlementService.RewardComputation> r2 = SettlementService.computeRewards(players, rule);

        assertThat(r1).isEqualTo(r2);
        // 并列第 1（a、m 同 90 分）：占名次 1、2，池 100+60=160 均分 80/80；z 第 3 名 30；b 第 4 名 0
        assertThat(r1).containsExactly(
                new SettlementService.RewardComputation("a", 1, 80),
                new SettlementService.RewardComputation("m", 1, 80),
                new SettlementService.RewardComputation("z", 3, 30),
                new SettlementService.RewardComputation("b", 4, 0));
    }

    @Test
    void defaultAmount_appliedToUnconfiguredRanks() {
        RewardRuleProperties withDefault =
                new RewardRuleProperties(60_000L, 5L, Map.of(1, 100L));
        List<PlayerResult> players = List.of(
                new PlayerResult("a", 10),
                new PlayerResult("b", 9));

        List<SettlementService.RewardComputation> r = SettlementService.computeRewards(players, withDefault);

        assertThat(r).containsExactly(
                new SettlementService.RewardComputation("a", 1, 100),
                new SettlementService.RewardComputation("b", 2, 5));
    }
}
