package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;
import com.github.shikejing.gamerewardoraclejava.common.ReportStatus;

import java.time.Instant;
import java.util.List;

public record ReportHistoryDto(long id,
                               long seq,
                               ReportStatus status,
                               String reason,
                               Instant reportedAt,
                               List<PlayerResult> players) {
}
