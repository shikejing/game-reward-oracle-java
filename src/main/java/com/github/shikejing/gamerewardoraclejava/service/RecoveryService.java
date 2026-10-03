package com.github.shikejing.gamerewardoraclejava.service;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.model.Match;
import com.github.shikejing.gamerewardoraclejava.repository.MatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 重启恢复服务。
 * <p>
 * H2 文件持久化保证已提交事务完整还原、未完成事务自动回滚（不残留半份结算）。
 * 启动时本服务：
 * <ol>
 *   <li>把修订窗口已关闭但仍处于 SETTLED 的对局推进为 REVISION_CLOSED；</li>
 *   <li>输出各状态对局计数：REPORTED 为未完成（结算未提交），其余为已提交，
 *       便于恢复后核对。</li>
 * </ol>
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final MatchRepository matchRepository;

    public RecoveryService(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onReady() {
        List<Match> expired = matchRepository.findSettledWithExpiredWindow(Instant.now());
        expired.forEach(m -> m.setStatus(MatchStatus.REVISION_CLOSED));
        matchRepository.saveAll(expired);

        Map<MatchStatus, Long> counts = new EnumMap<>(MatchStatus.class);
        for (MatchStatus s : MatchStatus.values()) {
            counts.put(s, matchRepository.countByStatus(s));
        }
        log.info("恢复完成：窗口关闭推进 {} 局；对局状态统计 {}（REPORTED=未完成，其余=已提交）",
                expired.size(), counts);
    }
}
