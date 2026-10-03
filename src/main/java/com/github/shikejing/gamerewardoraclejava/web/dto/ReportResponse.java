package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.MatchStatus;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;

/**
 * 上报处理结果。
 */
public record ReportResponse(String matchId,
                             long seq,
                             ReportStatus status,
                             String reason,
                             long currentSeq,
                             MatchStatus matchStatus) {
}
