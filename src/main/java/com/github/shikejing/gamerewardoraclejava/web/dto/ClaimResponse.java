package com.github.shikejing.gamerewardoraclejava.web.dto;

import java.time.Instant;

public record ClaimResponse(long claimId,
                            long distributionId,
                            String matchId,
                            String playerId,
                            long amount,
                            Instant claimedAt,
                            boolean alreadyExisted) {
}
