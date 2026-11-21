package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Settings for the cloud-drive pseudo-live feature. Credentials stay in the
 * app's private preferences and are never written to logs or shared storage.
 */
final class CloudLiveConfig {
    static final String PREFERENCES = "cloud_live";
    static final int DEFAULT_PORT = 18086;

    private static final String ENABLED = "enabled";
    private static final String PORT = "port";
    private static final String WINDOW_SIZE = "window_size";
    private static final String PARENT_FILE_ID = "parent_file_id";
    private static final String AUTHORIZATION = "authorization";
    private static final String MCLOUD_SIGN = "mcloud_sign";
    private static final String AUTO_REFRESH_INTERVAL = "auto_refresh_interval";
    private static final String X_TVG_URL = "x_tvg_url";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String DEBUG_LOG = "debug_log";
    private static final String LAN_ACCESS = "lan_access";
    private static final String WARMUP_ENABLED = "warmup_enabled";
    private static final String HOT_LIMIT = "hot_limit";
    private static final String HOT_DAYS = "hot_days";
    private static final String HOT_INTERVAL_SEC = "hot_interval_sec";

    final boolean enabled;
    final int port;
    final int windowSize;
    final String parentFileId;
    final String authorization;
    final String mcloudSign;
    final int autoRefreshInterval;
    final String xTvgUrl;
    final String refreshToken;
    final boolean debugLog;
    final boolean lanAccess;
    final boolean warmupEnabled;
    final int hotLimit;
    final int hotDays;
    final int hotIntervalSec;

    private CloudLiveConfig(boolean enabled, int port, int windowSize, String parentFileId,
            String authorization, String mcloudSign, int autoRefreshInterval, String xTvgUrl,
            String refreshToken, boolean debugLog, boolean lanAccess, boolean warmupEnabled,
            int hotLimit, int hotDays, int hotIntervalSec) {
        this.enabled = enabled;
        this.port = port;
        this.windowSize = windowSize;
        this.parentFileId = parentFileId;
        this.authorization = authorization;
        this.mcloudSign = mcloudSign;
        this.autoRefreshInterval = autoRefreshInterval;
        this.xTvgUrl = xTvgUrl;
        this.refreshToken = refreshToken;
        this.debugLog = debugLog;
        this.lanAccess = lanAccess;
        this.warmupEnabled = warmupEnabled;
        this.hotLimit = hotLimit;
        this.hotDays = hotDays;
        this.hotIntervalSec = hotIntervalSec;
    }

    static CloudLiveConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES,
                Context.MODE_PRIVATE);
        return new CloudLiveConfig(
                preferences.getBoolean(ENABLED, false),
                clamp(preferences.getInt(PORT, DEFAULT_PORT), 1024, 65535),
                clamp(preferences.getInt(WINDOW_SIZE, 8), 3, 30),
                preferences.getString(PARENT_FILE_ID, "").trim(),
                preferences.getString(AUTHORIZATION, "").trim(),
                preferences.getString(MCLOUD_SIGN, "").trim(),
                Math.max(60, preferences.getInt(AUTO_REFRESH_INTERVAL, 600)),
                preferences.getString(X_TVG_URL, "http://epg.51zmt.top:8000/e.xml").trim(),
                preferences.getString(REFRESH_TOKEN, "").trim(),
                preferences.getBoolean(DEBUG_LOG, true),
                preferences.getBoolean(LAN_ACCESS, true),
                preferences.getBoolean(WARMUP_ENABLED, false),
                clamp(preferences.getInt(HOT_LIMIT, 5), 1, 20),
                clamp(preferences.getInt(HOT_DAYS, 7), 1, 90),
                Math.max(10, preferences.getInt(HOT_INTERVAL_SEC, 60)));
    }

    boolean hasCredentials() {
        return authorization.length() > 0 && parentFileId.length() > 0;
    }

    boolean sameAs(CloudLiveConfig other) {
        return other != null
                && enabled == other.enabled
                && port == other.port
                && windowSize == other.windowSize
                && parentFileId.equals(other.parentFileId)
                && authorization.equals(other.authorization)
                && mcloudSign.equals(other.mcloudSign)
                && autoRefreshInterval == other.autoRefreshInterval
                && xTvgUrl.equals(other.xTvgUrl)
                && refreshToken.equals(other.refreshToken)
                && debugLog == other.debugLog
                && lanAccess == other.lanAccess
                && warmupEnabled == other.warmupEnabled
                && hotLimit == other.hotLimit
                && hotDays == other.hotDays
                && hotIntervalSec == other.hotIntervalSec;
    }

    static JSONObject describe(CloudLiveConfig config, String playlistUrl) throws JSONException {
        return new JSONObject()
                .put("enabled", config.enabled)
                .put("port", config.port)
                .put("windowSize", config.windowSize)
                .put("parentFileId", config.parentFileId)
                .put("authorizationSet", config.authorization.length() > 0)
                .put("mcloudSignSet", config.mcloudSign.length() > 0)
                .put("autoRefreshInterval", config.autoRefreshInterval)
                .put("xTvgUrl", config.xTvgUrl)
                .put("refreshTokenSet", config.refreshToken.length() > 0)
                .put("debugLog", config.debugLog)
                .put("lanAccess", config.lanAccess)
                .put("warmupEnabled", config.warmupEnabled)
                .put("hotLimit", config.hotLimit)
                .put("hotDays", config.hotDays)
                .put("hotIntervalSec", config.hotIntervalSec)
                .put("playlistUrl", playlistUrl == null ? "" : playlistUrl);
    }

    /**
     * Applies a partial update from the web management page. Empty credential
     * fields keep the stored value so the page never has to echo secrets back.
     */
    static void save(Context context, JSONObject request) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (request.has("enabled")) {
            editor.putBoolean(ENABLED, request.optBoolean("enabled", false));
        }
        if (request.has("port")) {
            int port = request.optInt("port", DEFAULT_PORT);
            if (port < 1024 || port > 65535) {
                throw new JSONException("端口需要在 1024 到 65535 之间");
            }
            editor.putInt(PORT, port);
        }
        if (request.has("windowSize")) {
            int windowSize = request.optInt("windowSize", 8);
            if (windowSize < 3 || windowSize > 30) {
                throw new JSONException("窗口大小需要在 3 到 30 之间");
            }
            editor.putInt(WINDOW_SIZE, windowSize);
        }
        if (request.has("parentFileId")) {
            editor.putString(PARENT_FILE_ID, request.optString("parentFileId", "").trim());
        }
        if (request.has("autoRefreshInterval")) {
            int interval = request.optInt("autoRefreshInterval", 600);
            if (interval < 60) {
                throw new JSONException("刷新间隔不能小于 60 秒");
            }
            editor.putInt(AUTO_REFRESH_INTERVAL, interval);
        }
        if (request.has("lanAccess")) {
            editor.putBoolean(LAN_ACCESS, request.optBoolean("lanAccess", true));
        }
        if (request.has("warmupEnabled")) {
            editor.putBoolean(WARMUP_ENABLED, request.optBoolean("warmupEnabled", false));
        }
        if (request.has("hotLimit")) {
            int hotLimit = request.optInt("hotLimit", 5);
            if (hotLimit < 1 || hotLimit > 20) {
                throw new JSONException("热点数量需要在 1 到 20 之间");
            }
            editor.putInt(HOT_LIMIT, hotLimit);
        }
        if (request.has("hotDays")) {
            int hotDays = request.optInt("hotDays", 7);
            if (hotDays < 1 || hotDays > 90) {
                throw new JSONException("统计天数需要在 1 到 90 之间");
            }
            editor.putInt(HOT_DAYS, hotDays);
        }
        if (request.has("hotIntervalSec")) {
            int hotIntervalSec = request.optInt("hotIntervalSec", 60);
            if (hotIntervalSec < 10) {
                throw new JSONException("热点刷新间隔不能小于 10 秒");
            }
            editor.putInt(HOT_INTERVAL_SEC, hotIntervalSec);
        }
        if (request.has("xTvgUrl")) {
            editor.putString(X_TVG_URL, request.optString("xTvgUrl", "").trim());
        }
        if (request.has("debugLog")) {
            editor.putBoolean(DEBUG_LOG, request.optBoolean("debugLog", true));
        }
        if (request.optBoolean("clearAuthorization", false)) {
            editor.remove(AUTHORIZATION);
        } else if (request.has("authorization")) {
            String value = request.optString("authorization", "").trim();
            if (value.length() > 0) {
                editor.putString(AUTHORIZATION, value);
            }
        }
        if (request.optBoolean("clearMcloudSign", false)) {
            editor.remove(MCLOUD_SIGN);
        } else if (request.has("mcloudSign")) {
            String value = request.optString("mcloudSign", "").trim();
            if (value.length() > 0) {
                editor.putString(MCLOUD_SIGN, value);
            }
        }
        if (request.optBoolean("clearRefreshToken", false)) {
            editor.remove(REFRESH_TOKEN);
        } else if (request.has("refreshToken")) {
            String value = request.optString("refreshToken", "").trim();
            if (value.length() > 0) {
                editor.putString(REFRESH_TOKEN, value);
            }
        }
        editor.apply();
    }

    /**
     * Cache directory. Kept on shared storage so channels survive an uninstall;
     * credentials never leave the app's private preferences.
     */
    static File storageRoot(Context context) {
        File external = writableExternalRoot();
        if (external != null) {
            return external;
        }
        return new File(context.getFilesDir(), "cloudlive");
    }

    static String storageLabel(Context context) {
        File external = writableExternalRoot();
        if (external != null) {
            return external.getAbsolutePath() + "（卸载后保留）";
        }
        return new File(context.getFilesDir(), "cloudlive").getAbsolutePath()
                + "（内部存储，卸载后清除）";
    }

    private static File writableExternalRoot() {
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                return null;
            }
            File root = new File(Environment.getExternalStorageDirectory(), "ntv/cloudlive");
            if (!root.isDirectory() && !root.mkdirs()) {
                return null;
            }
            File probe = new File(root, ".write-test");
            if (!probe.exists() && !probe.createNewFile()) {
                return null;
            }
            probe.delete();
            return root;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
