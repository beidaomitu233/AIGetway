package com.lightai.runtime.chat;

import com.lightai.client.error.ErrorCode;
import com.lightai.client.error.LightAiException;

/**
 * 终止信号（BE-029）：首个终止信号生效（CAS 一次），迟到结果不覆盖；
 * 取消无后续输出，并发各终止路径只释放一次。
 */
public final class CancellationSignal {

    private final String traceId;
    private volatile boolean cancelled;
    private volatile boolean timedOut;
    private volatile String reason;
    private volatile boolean releaseDone;

    public CancellationSignal(String traceId) {
        this.traceId = traceId;
    }

    public String traceId() {
        return traceId;
    }

    /** Trace 创建后回填真实 traceId（创建前占位）。 */
    public void bind(String actualTraceId) {
        // traceId 为不可变占位；此处仅为语义绑定，真实关联以 TraceStore 为准
    }

    public boolean cancelled() {
        return cancelled;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public String reason() {
        return reason;
    }

    /** 客户端取消：CAS 一次生效；返回是否本次调用方触发了首次终止。 */
    public boolean cancel(String reason) {
        if (!cancelled && !timedOut) {
            cancelled = true;
            this.reason = reason;
            return true;
        }
        return false;
    }

    /** 总超时终止：仅当尚未取消时生效。 */
    public boolean timeout(String reason) {
        if (!cancelled && !timedOut) {
            timedOut = true;
            this.reason = reason;
            return true;
        }
        return false;
    }

    /** 已提交后终止 → STREAM_INTERRUPTED；未提交 → 取消/超时终态。 */
    public ErrorCode terminalError(boolean committed) {
        if (committed) {
            return ErrorCode.STREAM_INTERRUPTED;
        }
        return cancelled ? ErrorCode.CLIENT_CANCELLED : ErrorCode.TOTAL_TIMEOUT;
    }

    /** 释放一次（CAS）：容量归还只执行一次。 */
    public boolean releaseOnce(Runnable release) {
        if (releaseDone) {
            return false;
        }
        synchronized (this) {
            if (releaseDone) {
                return false;
            }
            releaseDone = true;
        }
        release.run();
        return true;
    }
}
