package com.github.shikejing.gamerewardoraclejava.common;

/**
 * 上报的对局结果中的一名参与者成绩。
 *
 * @param playerId 参与者标识
 * @param score    得分（分数越高名次越靠前）
 */
public record PlayerResult(String playerId, long score) {
}
