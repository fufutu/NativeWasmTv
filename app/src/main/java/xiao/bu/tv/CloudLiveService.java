package xiao.bu.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the pseudo-live pipeline: config, catalog cache, loopback HTTP server and
 * the background refresh schedule. Started from {@link NtvApplication} so the
 * playlist source exists before the channel catalog is refreshed.
 */
final class CloudLiveService {
    private static final String TAG = "CloudLive";
    private static final String SOURCE_ID = "cloudlive";
    private static final String SOURCE_NAME = "云盘直播";
    private static final int WARMUP_LIMIT = 40;
    private static final long AUTH_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long AUTH_REFRESH_RETRY_MS = 60L * 60L * 1000L;
    private static final String AUTH_REFRESH_SUCCESS_AT = "auth_refresh_success_at";
    private static CloudLiveService instance;

    interface CatalogListener {
        void onCatalogChanged();
    }

    private final Context context;
    private volatile CloudLiveConfig config = null;
    private volatile CloudLiveStore store;
    private volatile CloudLiveServer server;
    private volatile ExecutorService worker;
    private volatile ScheduledExecutorService scheduler;
    private volatile CatalogListener catalogListener;
    private volatile boolean refreshing;
    private volatile long lastRefreshAt;
    private volatile long lastRefreshTookMs;
    private volatile int lastChannels;
    private volatile String lastError = "";
    private volatile String lastMode = "";
    private volatile String playlistUrl = "";
    private volatile String lanUrl = "";
    private volatile boolean catalogRefreshPending;
    private ExecutorService warmExecutor;
    private int refreshRetries;
    private String appliedStorageRoot = "";
    private volatile long hotCacheAt;
    private volatile long lastAuthRefreshAt;
    private volatile long lastAuthAttemptAt;
    private volatile String lastAuthRefreshError = "";
    private volatile JSONArray hotCache = new JSONArray();
    private volatile CloudLiveStore hotCacheStore;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastHitAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private CloudLiveService(Context context) {
        this.context = context.getApplicationContext();
    }

    static synchronized CloudLiveService get(Context context) {
        if (instance == null) {
            instance = new CloudLiveService(context);
        }
        return instance;
    }

    void setCatalogListener(CatalogListener listener) {
        catalogListener = listener;
    }

    /** Applies the current settings, starting or stopping the server as needed. */
    synchronized void startIfEnabled() {
        applyConfig(CloudLiveConfig.load(context), true);
    }

    /** Re-reads settings after the web page saved new values. */
    synchronized void restart() {
        applyConfig(CloudLiveConfig.load(context), false);
    }

    CloudLiveConfig config() {
        return config;
    }

    String refreshToken() {
        CloudLiveConfig current = config;
        return current == null ? "" : current.refreshToken;
    }

    String playlistUrl() {
        return playlistUrl;
    }

    String playlistText() {
        CloudLiveStore current = store;
        return current == null ? "" : current.playlistText();
    }

    boolean hasCatalogRefreshPending() {
        return catalogRefreshPending;
    }

    void clearCatalogRefreshPending() {
        catalogRefreshPending = false;
    }

    int hits(String channelId) {
        CloudLiveStore current = store;
        return current == null ? 0 : current.hits(channelId);
    }

    List<CloudLiveStore.SeasonRange> seasons(String channelId) {
        CloudLiveStore current = store;
        return current == null ? new ArrayList<CloudLiveStore.SeasonRange>()
                : current.seasons(channelId);
    }

    void requestStartEpisode(String channelId, int episodeIndex) {
        CloudLiveStore current = store;
        if (current != null) {
            current.requestStartEpisode(channelId, episodeIndex);
        }
    }

    CloudLiveTimeline.Window buildWindow(String channelId, boolean reset) {
        CloudLiveStore current = store;
        CloudLiveConfig settings = config;
        if (current == null || settings == null) {
            return null;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (current.isFolderChannel(channelId)) {
            CloudLiveApi.FolderMeta meta = current.folderMeta(channelId);
            if (meta == null || meta.episodes.isEmpty()) {
                return null;
            }
            int startEpisode = current.consumeStartEpisode(channelId);
            if (startEpisode >= 0) {
                // A voice command requested a specific season: begin the pseudo-live
                // stream at that episode and reset the anchor so it advances from there.
                reset = true;
            }
            CloudLiveTimeline.Window window = CloudLiveTimeline.folder(current,
                    executor(), channelId, meta, now, settings.windowSize, reset,
                    startEpisode);
            if (window != null) {
                recordChannelOpen(current, channelId, reset);
            }
            return window;
        }
        CloudLiveTimeline.Window window = CloudLiveTimeline.single(current, channelId, now,
                settings.windowSize, reset);
        if (window != null) {
            recordChannelOpen(current, channelId, reset);
        }
        return window;
    }

    /**
     * HLS clients reload the playlist every few seconds. Count an open only once
     * per minute per channel so hot ranking reflects opens rather than reloads.
     */
    private void recordChannelOpen(CloudLiveStore store, String channelId, boolean reset) {
        long now = System.currentTimeMillis();
        Long previous = lastHitAt.get(channelId);
        if (!reset && previous != null && now - previous.longValue() < 60000L) {
            return;
        }
        lastHitAt.put(channelId, now);
        store.recordHit(channelId);
    }

    /** Returns false when a refresh is already running or the service is stopped. */
    boolean requestRefresh(final boolean force) {
        final CloudLiveStore target = store;
        final ExecutorService executor = worker;
        if (target == null || executor == null) {
            return false;
        }
        synchronized (this) {
            if (refreshing) {
                return false;
            }
            refreshing = true;
        }
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    runRefresh(target, force);
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (this) {
                refreshing = false;
            }
            return false;
        }
        return true;
    }

    private void runRefresh(final CloudLiveStore target, final boolean force) {
        long started = System.currentTimeMillis();
        String before = target.playlistText();
        String error = "";
        int channels = 0;
        try {
            ensureAuthorization(target);
            channels = target.refresh(force);
        } catch (IOException failure) {
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            AppLog.w(TAG, "Refresh failed: " + error);
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName();
            AppLog.w(TAG, "Refresh crashed", failure);
        } finally {
            // Mirrors the Python service: the running flag is always cleared,
            // even when refresh dies with an Error.
            synchronized (CloudLiveService.this) {
                refreshing = false;
                lastRefreshAt = System.currentTimeMillis();
                lastRefreshTookMs = lastRefreshAt - started;
                lastChannels = channels;
                lastError = error;
                lastMode = force ? "force" : "incremental";
            }
        }
        String after = target.playlistText();
        if (error.length() == 0 && !after.equals(before)) {
            catalogRefreshPending = true;
            CatalogListener listener = catalogListener;
            if (listener != null) {
                listener.onCatalogChanged();
            }
        }
        if (error.length() == 0) {
            refreshRetries = 0;
            scheduleWarmup();
        } else if (refreshRetries < 2) {
            // Network may not be up yet right after boot; retry shortly.
            refreshRetries++;
            final int attempt = refreshRetries;
            final ScheduledExecutorService scheduled = scheduler;
            if (scheduled != null) {
                try {
                    scheduled.schedule(new Runnable() {
                        @Override public void run() {
                            requestRefresh(force);
                        }
                    }, 30L * attempt, TimeUnit.SECONDS);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** Refreshes at most once a day while running; failures retry at most hourly. */
    private void ensureAuthorization(CloudLiveStore target) {
        CloudLiveConfig settings = config;
        if (settings == null || settings.userId.length() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long expiresAt = settings.authorizationExpiresAt();
        boolean due = lastAuthRefreshAt <= 0
                || now - lastAuthRefreshAt >= AUTH_REFRESH_INTERVAL_MS;
        boolean expiring = expiresAt > 0 && expiresAt <= now + AUTH_REFRESH_INTERVAL_MS;
        if (!due && !expiring) {
            return;
        }
        if (now - lastAuthAttemptAt < AUTH_REFRESH_RETRY_MS) {
            return;
        }
        lastAuthAttemptAt = now;
        if (!target.refreshAuthorization()) {
            lastAuthRefreshError = "授权自动延期失败，将每小时重试";
        }
    }

    private void persistRefreshedAuthorization(String authorization) {
        try {
            CloudLiveConfig.save(context, new JSONObject().put("authorization", authorization));
            long now = System.currentTimeMillis();
            getSharedPreferences(CloudLiveConfig.PREFERENCES, MODE_PRIVATE).edit()
                    .putLong(AUTH_REFRESH_SUCCESS_AT, now).apply();
            CloudLiveConfig settings = config;
            if (settings != null) {
                config = settings.withAuthorization(authorization);
            }
            lastAuthRefreshAt = now;
            lastAuthRefreshError = "";
            long expiresAt = CloudLiveConfig.authorizationExpiresAt(authorization);
            AppLog.i(TAG, "Cloud authorization saved, expiresAt=" + expiresAt);
        } catch (JSONException error) {
            lastAuthRefreshError = "授权已延期，但保存失败";
        }
    }

    /**
     * Pre-fetches segment lists for the channels people open most (playlist
     * order when there is no history), so the first click does not wait for
     * the cloud API.
     */
    private void scheduleWarmup() {
        CloudLiveConfig settings = config;
        if (settings == null || !settings.warmupEnabled) {
            return;
        }
        final CloudLiveStore target = store;
        final ExecutorService executor = warmExecutor;
        if (target == null || executor == null) {
            return;
        }
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        List<String> ids = target.warmupTargets(WARMUP_LIMIT);
                        for (String id : ids) {
                            if (store != target) {
                                return;
                            }
                            if (target.cachedSegments(id) == null) {
                                target.ensureSegments(id);
                            }
                        }
                        target.saveHits();
                    } catch (RuntimeException error) {
                        AppLog.w(TAG, "Warmup failed", error);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            AppLog.w(TAG, "Unable to schedule warmup");
        }
    }

    /** True when the local server is up and the catalog is loaded. */
    boolean isRunning() {
        return store != null;
    }

    JSONObject statusJson() {
        CloudLiveConfig settings = config;
        CloudLiveStore current = store;
        boolean enabled = settings != null && settings.enabled;
        String startupError = "";
        if (current == null) {
            startupError = lastError.length() > 0 ? lastError
                    : enabled ? "服务未启动" : "未启用";
        }
        try {
            return new JSONObject()
                    .put("enabled", enabled)
                    .put("running", current != null)
                    .put("startupError", startupError)
                    .put("playlistUrl", playlistUrl)
                    .put("channelCount", current == null ? 0 : current.channelCount())
                    .put("refreshing", refreshing)
                    .put("lastRefreshAt", lastRefreshAt)
                    .put("lastRefreshChannels", lastChannels)
                    .put("lastRefreshTookMs", lastRefreshTookMs)
                    .put("lastRefreshMode", lastMode)
                    .put("lastRefreshError", lastError)
                    .put("lanUrl", lanUrl)
                    .put("directories", current == null ? 0 : current.directoryCount())
                    .put("videos", current == null ? 0 : current.videoCount())
                    .put("folders", current == null ? 0 : current.folderCount())
                    .put("authorizationExpiresAt",
                            settings == null ? 0 : settings.authorizationExpiresAt())
                    .put("authorizationRefreshedAt", lastAuthRefreshAt)
                    .put("authorizationRefreshError", lastAuthRefreshError)
                    .put("hotChannels", hotChannels(settings, current));
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    /**
     * Most opened channels for the management page. Recomputed at most once per
     * configured interval because the page polls status while it is open.
     */
    private JSONArray hotChannels(CloudLiveConfig settings, CloudLiveStore current) {
        if (settings == null || current == null) {
            return new JSONArray();
        }
        long now = System.currentTimeMillis();
        if (hotCacheStore == current
                && now - hotCacheAt < Math.max(10, settings.hotIntervalSec) * 1000L) {
            return hotCache;
        }
        JSONArray fresh = current.hotChannels(settings.hotLimit, settings.hotDays);
        hotCache = fresh;
        hotCacheStore = current;
        hotCacheAt = now;
        return fresh;
    }

    private synchronized void applyConfig(CloudLiveConfig updated, boolean refreshAfterStart) {
        File root = CloudLiveConfig.storageRoot(context);
        // Saving the same settings again must not tear down a running server
        // (and kill an in-flight refresh). A storage change does need a restart.
        if (updated.enabled && store != null && config != null && config.sameAs(updated)
                && root.getAbsolutePath().equals(appliedStorageRoot)) {
            return;
        }
        stopInternal();
        appliedStorageRoot = root.getAbsolutePath();
        config = updated;
        if (!updated.enabled) {
            setSourceEnabled(false);
            lastError = "未启用";
            return;
        }
        CloudLiveApi api = new CloudLiveApi(updated);
        CloudLiveStore freshStore = new CloudLiveStore(root, updated, api);
        freshStore.loadFromDisk();
        lastAuthRefreshAt = getSharedPreferences(CloudLiveConfig.PREFERENCES, MODE_PRIVATE)
                .getLong(AUTH_REFRESH_SUCCESS_AT, 0);
        lastAuthAttemptAt = 0;
        lastAuthRefreshError = "";
        freshStore.setAuthorizationListener(new CloudLiveApi.AuthorizationListener() {
            @Override public void onAuthorizationChanged(String authorization) {
                persistRefreshedAuthorization(authorization);
            }
        });
        worker = Executors.newFixedThreadPool(2, daemonFactory("cloud-live-worker"));
        warmExecutor = Executors.newSingleThreadExecutor(daemonFactory("cloud-live-warm"));
        scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("cloud-live-scheduler"));
        CloudLiveServer freshServer = new CloudLiveServer(this);
        try {
            int port = freshServer.start(updated.port, updated.lanAccess);
            freshStore.setServerPort(port);
            freshStore.rebuildPlaylist();
            playlistUrl = "http://127.0.0.1:" + port + "/playlist.m3u";
            lanUrl = updated.lanAccess ? detectLanUrl(port) : "";
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to start pseudo-live server", error);
            lastError = "本地端口启动失败";
            freshServer.stop();
            shutdownExecutors();
            return;
        }
        store = freshStore;
        server = freshServer;
        boolean hasContent = updated.hasCredentials()
                || freshStore.playlistText().length() > 0;
        if (!hasContent) {
            setSourceEnabled(false);
            lastError = "请填写云盘根目录 ID 和授权信息";
            freshServer.stop();
            server = null;
            store = null;
            playlistUrl = "";
            shutdownExecutors();
            return;
        }
        if (ensureSource(playlistUrl, true)) {
            catalogRefreshPending = true;
        }
        lastError = "";
        final ScheduledExecutorService scheduled = scheduler;
        scheduled.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    requestRefresh(false);
                } catch (RuntimeException error) {
                    AppLog.w(TAG, "Scheduled refresh failed", error);
                }
            }
        }, updated.autoRefreshInterval, updated.autoRefreshInterval, TimeUnit.SECONDS);
        if (!refreshAfterStart && freshStore.playlistText().length() > 0) {
            return;
        }
        // Start with an incremental refresh; only the manual button forces a full scan.
        requestRefresh(false);
    }

    private synchronized void stopInternal() {
        CloudLiveServer currentServer = server;
        server = null;
        if (currentServer != null) {
            currentServer.stop();
        }
        shutdownExecutors();
        refreshing = false;
        refreshRetries = 0;
        store = null;
        playlistUrl = "";
        lanUrl = "";
    }

    /** Human readable page for debugging from another machine on the LAN. */
    String debugPage() {
        StringBuilder builder = new StringBuilder();
        builder.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta http-equiv=\"refresh\" content=\"3\">")
                .append("<title>云盘直播状态</title></head>")
                .append("<body style=\"font-family:monospace;padding:16px;line-height:1.5\">")
                .append("<h3>云盘直播状态</h3><pre>")
                .append(escapeHtml(statusJson().toString()))
                .append("</pre><h3>播放列表</h3><pre>")
                .append(escapeHtml(playlistText()))
                .append("</pre></body></html>");
        return builder.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String detectLanUrl(int port) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses =
                        network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address
                            && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (host != null && host.length() > 0) {
                            return "http://" + host + ":" + port + "/debug";
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void shutdownExecutors() {
        ScheduledExecutorService currentScheduler = scheduler;
        scheduler = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        ExecutorService currentWorker = worker;
        worker = null;
        if (currentWorker != null) {
            currentWorker.shutdownNow();
        }
        ExecutorService currentWarm = warmExecutor;
        warmExecutor = null;
        if (currentWarm != null) {
            currentWarm.shutdownNow();
        }
    }

    private ExecutorService executor() {
        return worker;
    }

    private boolean ensureSource(String location, boolean enabled) {
        try {
            PlaylistManager manager = new PlaylistManager(context);
            return manager.ensureSource(SOURCE_ID, SOURCE_NAME, location, enabled);
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to register pseudo-live source", error);
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to register pseudo-live source", error);
        }
        return false;
    }

    private void setSourceEnabled(boolean enabled) {
        try {
            new PlaylistManager(context).ensureSource(SOURCE_ID, SOURCE_NAME, null, enabled);
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to update pseudo-live source", error);
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to update pseudo-live source", error);
        }
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
