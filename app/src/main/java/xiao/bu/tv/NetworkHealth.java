package xiao.bu.tv;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络健康判定器：只聚合“应用自己请求的结果”，不做任何主动探测。
 *
 * <p>被路由器/iptables 封网时链路仍在、系统依旧报“已联网”，也没有任何广播，
 * 唯一可靠的证据是应用发出的请求持续失败。判定口径参照主流客户端做法：
 * <ul>
 *   <li>多个不同目标同时出现传输层失败 → 链路不可用；</li>
 *   <li>零成功且失败次数持续累积 → 同样视为链路不可用；</li>
 *   <li>只有单个目标失败、或对方明确拒绝连接 → 归为目标/线路问题；</li>
 *   <li>任何一次请求成功即清除证据，出帧即恢复。</li>
 * </ul>
 * 判定只发生在这里，播放代码只负责上报，界面只读取状态。
 */
final class NetworkHealth {

    /** 失败类型。HTTP_ERROR / STALL 属于内容层问题，不参与“链路不可用”判定。 */
    enum Kind { DNS_FAIL, CONNECT_TIMEOUT, CONNECT_REFUSED, RESET, HTTP_ERROR, STALL }

    enum State { OK, SOURCE_DOWN, LINK_DOWN }

    private static final long WINDOW_MS = 60000L;
    private static final long NO_SUCCESS_MS = 20000L;
    private static final int LINK_FAILURE_COUNT = 2;
    private static final int LINK_DISTINCT_HOSTS = 2;

    private static final class Failure {
        final long at;
        final String host;
        final Kind kind;

        Failure(long at, String host, Kind kind) {
            this.at = at;
            this.host = host;
            this.kind = kind;
        }
    }

    private final Object lock = new Object();
    private final ArrayList<Failure> failures = new ArrayList<>();
    private long lastSuccessAt = Long.MIN_VALUE / 2;
    private volatile State state = State.OK;

    State state() {
        return state;
    }

    boolean isLinkDown() {
        return state == State.LINK_DOWN;
    }

    void reportSuccess(String host) {
        synchronized (lock) {
            lastSuccessAt = SystemClock.elapsedRealtime();
            failures.clear();
            setState(State.OK, "success host=" + host);
        }
    }

    void reportFailure(String host, Kind kind) {
        if (kind == null) return;
        synchronized (lock) {
            long now = SystemClock.elapsedRealtime();
            failures.add(new Failure(now, host == null ? "" : host, kind));
            prune(now);
            setState(recompute(now), "failure host=" + host + " kind=" + kind);
        }
    }

    void reset() {
        synchronized (lock) {
            failures.clear();
            setState(State.OK, "reset");
        }
    }

    private void setState(State next, String why) {
        if (next == state) return;
        state = next;
        AppLog.i("NetworkHealth", "state " + next + " because " + why);
    }

    private void prune(long now) {
        for (int i = failures.size() - 1; i >= 0; i--) {
            if (now - failures.get(i).at > WINDOW_MS) {
                failures.remove(i);
            }
        }
    }

    private State recompute(long now) {
        int transport = 0;
        int refused = 0;
        HashSet<String> hosts = new HashSet<>();
        for (Failure failure : failures) {
            switch (failure.kind) {
                case DNS_FAIL:
                case CONNECT_TIMEOUT:
                case RESET:
                    transport++;
                    if (failure.host.length() > 0) {
                        hosts.add(failure.host);
                    }
                    break;
                case CONNECT_REFUSED:
                    refused++;
                    break;
                default:
                    break;
            }
        }
        if (hosts.size() >= LINK_DISTINCT_HOSTS) {
            return State.LINK_DOWN;
        }
        if (transport >= LINK_FAILURE_COUNT && now - lastSuccessAt >= NO_SUCCESS_MS) {
            return State.LINK_DOWN;
        }
        if (transport > 0 || refused > 0) {
            return State.SOURCE_DOWN;
        }
        return State.OK;
    }

    /** 异常文本归类，只做字符串分类，不解析端口、不发起任何连接。 */
    static Kind classify(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        if (t.contains("econnrefused") || t.contains("connection refused")) {
            return Kind.CONNECT_REFUSED;
        }
        if (t.contains("unknownhost") || t.contains("unable to resolve host")
                || t.contains("err_name_not_resolved")) {
            return Kind.DNS_FAIL;
        }
        if (t.contains("enetunreach") || t.contains("network is unreachable")) {
            return Kind.CONNECT_TIMEOUT;
        }
        if (t.contains("timeout") || t.contains("timed out") || t.contains("超时")) {
            return Kind.CONNECT_TIMEOUT;
        }
        if (t.contains("reset")) {
            return Kind.RESET;
        }
        if (t.contains("buffering") || t.contains("stalled") || t.contains("hls error")) {
            return Kind.STALL;
        }
        if (t.contains("http") || t.contains("status")) {
            return Kind.HTTP_ERROR;
        }
        return Kind.STALL;
    }

    private static final Pattern CONNECT_FAILURE = Pattern.compile(
            "failed to connect to ([^/\\s]+)");

    /** 取 URL 的主机名，仅用于失败目标计数。 */
    static String hostOfUrl(String url) {
        if (url == null || url.length() == 0) return "";
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** 从异常文本里取出失败目标，仅用于“是否多个目标都在失败”的计数。 */
    static String hostOf(String text) {
        if (text == null || text.length() == 0) return "";
        String lower = text.toLowerCase(Locale.US);
        Matcher matcher = CONNECT_FAILURE.matcher(lower);
        if (matcher.find()) {
            String host = matcher.group(1);
            if (host.length() > 0) return host;
        }
        return "";
    }
}
