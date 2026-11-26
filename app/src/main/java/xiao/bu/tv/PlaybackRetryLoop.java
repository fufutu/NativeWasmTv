package xiao.bu.tv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 播放失败重试循环。
 *
 * <p>只做一件事：这一路起不来就一直按退避间隔重试。不认识界面、不认识播放器、
 * 也不解析异常文本；外部只需要在“这一路失败”“这一路成功”时各通知一次。
 * 所有状态都只在主线程上变更，调用方在什么线程通知都可以。
 */
final class PlaybackRetryLoop {

    interface Listener {
        /** 该发起下一轮播放尝试。 */
        void onRetryDue();

        /** 仍在失败：刷新等候面板上的次数与倒计时。 */
        void onWaiting(int attempt, long delayMs);

        /** 已恢复。 */
        void onRecovered();
    }

    private static final long FIRST_DELAY_MS = 5000L;
    private static final long MAX_DELAY_MS = 30000L;
    /** 同一次尝试里重复回调的合并窗口。 */
    private static final long DUPLICATE_WINDOW_MS = 1000L;
    /** 一次尝试的最长等待：子步骤卡死也不会让循环永远停住。 */
    private static final long ATTEMPT_TIMEOUT_MS = 60000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryTask = this::fireRetry;
    private final Runnable failureTask = this::handleFailure;
    private final Runnable timeoutTask = this::handleAttemptTimeout;
    private Listener listener;
    private int attempt;
    private long lastFailureAtMs = Long.MIN_VALUE / 2;
    private boolean waiting;

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isWaiting() {
        return waiting;
    }

    void onAttemptFailed() {
        runOnMain(failureTask);
    }

    void onAttemptSucceeded() {
        runOnMain(this::handleSuccess);
    }

    void cancel() {
        runOnMain(this::handleCancel);
    }

    private void handleFailure() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFailureAtMs < DUPLICATE_WINDOW_MS) {
            return;
        }
        lastFailureAtMs = now;
        attempt++;
        waiting = true;
        long delay = delayOf(attempt);
        AppLog.w("PlaybackRetry", "attempt " + attempt + " failed, retry in " + delay + "ms");
        if (listener != null) {
            listener.onWaiting(attempt, delay);
        }
        handler.removeCallbacks(retryTask);
        handler.removeCallbacks(timeoutTask);
        handler.postDelayed(retryTask, delay);
    }

    private void handleSuccess() {
        if (!waiting && attempt == 0) {
            return;
        }
        AppLog.i("PlaybackRetry", "recovered after " + attempt + " attempt(s)");
        attempt = 0;
        waiting = false;
        handler.removeCallbacks(retryTask);
        handler.removeCallbacks(timeoutTask);
        if (listener != null) {
            listener.onRecovered();
        }
    }

    private void handleCancel() {
        attempt = 0;
        waiting = false;
        handler.removeCallbacks(retryTask);
        handler.removeCallbacks(failureTask);
        handler.removeCallbacks(timeoutTask);
    }

    private void fireRetry() {
        if (!waiting || listener == null) {
            return;
        }
        listener.onRetryDue();
        // 这一轮要是既不成功也不报错（某个子步骤静默死掉），到点当失败处理。
        handler.removeCallbacks(timeoutTask);
        handler.postDelayed(timeoutTask, ATTEMPT_TIMEOUT_MS);
    }

    private void handleAttemptTimeout() {
        if (!waiting) {
            return;
        }
        AppLog.w("PlaybackRetry", "attempt " + attempt + " gave no result in "
                + ATTEMPT_TIMEOUT_MS + "ms, retrying");
        handleFailure();
    }

    private void runOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            handler.post(task);
        }
    }

    private static long delayOf(int attempt) {
        long delay = FIRST_DELAY_MS << Math.min(Math.max(attempt - 1, 0), 3);
        return Math.min(MAX_DELAY_MS, delay);
    }
}
