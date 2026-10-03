package com.github.shikejing.reward.service;

import com.github.shikejing.reward.domain.Model.MatchReport;
import com.github.shikejing.reward.domain.Model.PlayerScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 本地模拟预言机：在本进程内生成并上报对局结果，
 * 不依赖任何外部服务与真实凭据。seed 固定时结果可复现。
 */
@Service
public class OracleSimulator {

    private final MatchService matchService;

    public OracleSimulator(MatchService matchService) {
        this.matchService = matchService;
    }

    /** 模拟一次预言机上报：由 seed 确定性生成参与者分数。 */
    public MatchReport simulateReport(String matchId, long reportSeq,
                                      List<String> playerIds, long seed) {
        Random random = new Random(seed);
        List<PlayerScore> scores = new ArrayList<>();
        for (String pid : playerIds) {
            scores.add(new PlayerScore(pid, random.nextInt(1000)));
        }
        return matchService.submitReport(matchId, reportSeq, scores);
    }
}
