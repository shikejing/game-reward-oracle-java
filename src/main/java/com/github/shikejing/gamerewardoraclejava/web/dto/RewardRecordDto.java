package com.github.shikejing.gamerewardoraclejava.web.dto;

import com.github.shikejing.gamerewardoraclejava.common.RewardRecordStatus;

public record RewardRecordDto(String playerId,
                              int rank,
                              long amount,
                              RewardRecordStatus status) {
}
