package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.PlayerResult;

import java.util.List;

/**
 * 预言机上报请求。
 */
public record ReportRequest(String matchId, Long seq, List<PlayerResult> players) {
}
