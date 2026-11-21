package xiao.bu.tv;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
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
    private volatile boolean catalogRefreshPending;

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
        applyConfig(CloudLiveConfig.load(context));
    }

    /** Re-reads settings after the web page saved new values. */
    synchronized void restart() {
        applyConfig(CloudLiveConfig.load(context));
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
            return CloudLiveTimeline.folder(current, executor(), channelId, meta, now,
                    settings.windowSize, reset);
        }
        return CloudLiveTimeline.single(current, channelId, now, settings.windowSize, reset);
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
            channels = target.refresh(force);
        } catch (IOException failure) {
            error = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            AppLog.w(TAG, "Refresh failed: " + error);
        } catch (RuntimeException failure) {
            error = failure.getClass().getSimpleName();
            AppLog.w(TAG, "Refresh crashed", failure);
        }
        String after = target.playlistText();
        synchronized (CloudLiveService.this) {
            refreshing = false;
            lastRefreshAt = System.currentTimeMillis();
            lastRefreshTookMs = lastRefreshAt - started;
            lastChannels = channels;
            lastError = error;
            lastMode = force ? "force" : "incremental";
        }
        if (error.length() == 0 && !after.equals(before)) {
            catalogRefreshPending = true;
            CatalogListener listener = catalogListener;
            if (listener != null) {
                listener.onCatalogChanged();
            }
        }
    }

    JSONObject statusJson() {
        CloudLiveConfig settings = config;
        CloudLiveStore current = store;
        try {
            return new JSONObject()
                    .put("enabled", settings != null && settings.enabled)
                    .put("running", current != null)
                    .put("playlistUrl", playlistUrl)
                    .put("channelCount", current == null ? 0 : current.channelCount())
                    .put("refreshing", refreshing)
                    .put("lastRefreshAt", lastRefreshAt)
                    .put("lastRefreshChannels", lastChannels)
                    .put("lastRefreshTookMs", lastRefreshTookMs)
                    .put("lastRefreshMode", lastMode)
                    .put("lastRefreshError", lastError);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    private synchronized void applyConfig(CloudLiveConfig updated) {
        stopInternal();
        config = updated;
        if (!updated.enabled) {
            setSourceEnabled(false);
            return;
        }
        File root = new File(context.getFilesDir(), "cloudlive");
        CloudLiveApi api = new CloudLiveApi(updated);
        CloudLiveStore freshStore = new CloudLiveStore(root, updated, api);
        freshStore.loadFromDisk();
        worker = Executors.newFixedThreadPool(2, daemonFactory("cloud-live-worker"));
        scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("cloud-live-scheduler"));
        CloudLiveServer freshServer = new CloudLiveServer(this);
        try {
            int port = freshServer.start(updated.port);
            freshStore.setServerPort(port);
            freshStore.rebuildPlaylist();
            playlistUrl = "http://127.0.0.1:" + port + "/playlist.m3u";
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
        if (freshStore.playlistText().length() == 0) {
            requestRefresh(true);
        }
    }

    private synchronized void stopInternal() {
        CloudLiveServer currentServer = server;
        server = null;
        if (currentServer != null) {
            currentServer.stop();
        }
        shutdownExecutors();
        refreshing = false;
        store = null;
        playlistUrl = "";
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
