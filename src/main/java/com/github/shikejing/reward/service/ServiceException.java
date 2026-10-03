package com.github.shikejing.reward.service;

/** 服务层可区分失败原因。 */
public class ServiceException extends RuntimeException {
    public enum Reason {
        DUPLICATE_REPORT_CONFLICT,   // 同序号但内容不同
        REVISION_WINDOW_CLOSED,      // 超出修订窗口
        ALREADY_SETTLED,             // 已结算，拒绝修订/迟到上报改写
        REPORT_NOT_FOUND,
        MATCH_NOT_FOUND,
        NOT_SETTLED,                 // 尚未结算不能发放
        ALREADY_GRANTED
    }
    private final Reason reason;
    public ServiceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
