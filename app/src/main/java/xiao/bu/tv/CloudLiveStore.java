package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disk backed catalog for the cloud-drive pseudo-live channels. The layout
 * mirrors the original Python service: per-directory channel snapshots,
 * per-folder episode metadata, per-video segment lists and per-channel anchors.
 */
final class CloudLiveStore {
    private static final String TAG = "CloudLive";
    private static final long SEGMENT_TTL_MS = 82800L * 1000L;
    private static final long SEGMENT_FAIL_BACKOFF_MS = 30000L;

    static final class DirChild {
        final String type;
        final String fileId;
        final String name;

        DirChild(String type, String fileId, String name) {
            this.type = type;
            this.fileId = fileId;
            this.name = name;
        }
    }

    private final File root;
    private final File segmentsDir;
    private final File channelsDir;
    private final File foldersDir;
    private final File anchorsDir;
    private final File namesFile;
    private final File playlistFile;
    private final CloudLiveApi api;
    private final CloudLiveConfig config;

    private final Object cacheLock = new Object();
    private final Object refreshLock = new Object();
    private final Map<String, Object> segmentLocks = new HashMap<String, Object>();
    private final Set<String> prefetching = new HashSet<String>();

    private final Map<String, List<DirChild>> dirChildren =
            new LinkedHashMap<String, List<DirChild>>();
    private final Map<String, CloudLiveApi.FolderMeta> folderMeta =
            new LinkedHashMap<String, CloudLiveApi.FolderMeta>();
    private final Map<String, CloudLiveApi.Segments> segmentMemory =
            new HashMap<String, CloudLiveApi.Segments>();
    private final Map<String, Long> segmentSavedAt = new HashMap<String, Long>();
    private final Map<String, Long> segmentFailUntil = new HashMap<String, Long>();
    private final Map<String, Double> anchors = new HashMap<String, Double>();
    private final Map<String, String> parentNames = new LinkedHashMap<String, String>();
    private final Map<String, Map<String, String>> secondNames =
            new HashMap<String, Map<String, String>>();
    private final Set<String> folderChannels = new HashSet<String>();
    private final Set<String> videoChannels = new HashSet<String>();

    private volatile String playlistText = "";
    private volatile int serverPort = CloudLiveConfig.DEFAULT_PORT;

    CloudLiveStore(File root, CloudLiveConfig config, CloudLiveApi api) {
        this.root = root;
        this.config = config;
        this.api = api;
        segmentsDir = new File(root, "_segments");
        channelsDir = new File(root, "_channels");
        foldersDir = new File(root, "_folders");
        anchorsDir = new File(root, "_anchors");
        namesFile = new File(root, "_names.json");
        playlistFile = new File(root, "playlist.m3u");
    }

    void setServerPort(int port) {
        serverPort = port;
    }

    /** Rebuilds the playlist with the current port from the loaded cache. */
    void rebuildPlaylist() {
        publishPlaylist();
    }

    String playlistText() {
        return playlistText;
    }

    int channelCount() {
        String text = playlistText;
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("#EXTINF", index)) >= 0) {
            count++;
            index += 7;
        }
        return count;
    }

    void loadFromDisk() {
        ensureDirectories();
        JSONObject names = readJson(namesFile);
        if (names != null) {
            JSONObject parent = names.optJSONObject("parent");
            if (parent != null) {
                for (java.util.Iterator<String> it = parent.keys(); it.hasNext(); ) {
                    String key = it.next();
                    parentNames.put(key, parent.optString(key, ""));
                }
            }
            JSONObject second = names.optJSONObject("second");
            if (second != null) {
                for (java.util.Iterator<String> it = second.keys(); it.hasNext(); ) {
                    String dirId = it.next();
                    JSONObject third = second.optJSONObject(dirId);
                    Map<String, String> values = new HashMap<String, String>();
                    if (third != null) {
                        for (java.util.Iterator<String> child = third.keys(); child.hasNext(); ) {
                            String key = child.next();
                            values.put(key, third.optString(key, ""));
                        }
                    }
                    secondNames.put(dirId, values);
                }
            }
        }
        File[] channelFiles = channelsDir.listFiles();
        if (channelFiles != null) {
            for (File file : channelFiles) {
                if (!file.getName().endsWith(".json")) {
                    continue;
                }
                JSONObject json = readJson(file);
                if (json == null) {
                    continue;
                }
                String dirId = file.getName().substring(0, file.getName().length() - 5);
                JSONArray childrenJson = json.optJSONArray("children");
                List<DirChild> children = parseDirChildren(childrenJson);
                dirChildren.put(dirId, children);
                for (DirChild child : children) {
                    if ("video".equals(child.type)) {
                        videoChannels.add(child.fileId);
                    }
                }
            }
        }
        File[] folderFiles = foldersDir.listFiles();
        if (folderFiles != null) {
            for (File file : folderFiles) {
                if (!file.getName().endsWith(".json")) {
                    continue;
                }
                JSONObject json = readJson(file);
                CloudLiveApi.FolderMeta meta = parseFolderMeta(json);
                if (meta != null) {
                    String folderId = file.getName().substring(0, file.getName().length() - 5);
                    folderMeta.put(folderId, meta);
                    folderChannels.add(folderId);
                }
            }
        }
        String saved = readText(playlistFile);
        if (saved != null) {
            playlistText = saved;
        }
        AppLog.i(TAG, "Loaded cache: " + dirChildren.size() + " directories, "
                + folderMeta.size() + " folders, " + videoChannels.size() + " videos");
    }

    List<DirChild> dirChildren(String dirId) {
        synchronized (cacheLock) {
            List<DirChild> cached = dirChildren.get(dirId);
            return cached == null ? null : new ArrayList<DirChild>(cached);
        }
    }

    CloudLiveApi.FolderMeta folderMeta(String folderId) {
        synchronized (cacheLock) {
            CloudLiveApi.FolderMeta cached = folderMeta.get(folderId);
            if (cached != null) {
                return cached;
            }
        }
        JSONObject json = readJson(new File(foldersDir, folderId + ".json"));
        CloudLiveApi.FolderMeta loaded = parseFolderMeta(json);
        if (loaded != null) {
            synchronized (cacheLock) {
                folderMeta.put(folderId, loaded);
            }
        }
        return loaded;
    }

    boolean isFolderChannel(String channelId) {
        synchronized (cacheLock) {
            if (folderChannels.contains(channelId)) {
                return true;
            }
            if (videoChannels.contains(channelId)) {
                return false;
            }
        }
        return folderMeta(channelId) != null;
    }

    /** Cached segments only; never blocks on the network. */
    CloudLiveApi.Segments cachedSegments(String fileId) {
        synchronized (cacheLock) {
            CloudLiveApi.Segments cached = segmentMemory.get(fileId);
            if (cached != null && !isSegmentExpired(segmentSavedAt.get(fileId))) {
                return cached;
            }
            if (cached != null) {
                segmentMemory.remove(fileId);
                segmentSavedAt.remove(fileId);
            }
        }
        CloudLiveApi.Segments disk = readSegments(fileId);
        if (disk != null) {
            synchronized (cacheLock) {
                segmentMemory.put(fileId, disk);
                segmentSavedAt.put(fileId, System.currentTimeMillis());
            }
        }
        return disk;
    }

    CloudLiveApi.Segments ensureSegments(String fileId) {
        CloudLiveApi.Segments cached = cachedSegments(fileId);
        if (cached != null) {
            return cached;
        }
        Object lock;
        synchronized (segmentLocks) {
            lock = segmentLocks.get(fileId);
            if (lock == null) {
                lock = new Object();
                segmentLocks.put(fileId, lock);
            }
        }
        synchronized (lock) {
            cached = cachedSegments(fileId);
            if (cached != null) {
                return cached;
            }
            synchronized (cacheLock) {
                Long until = segmentFailUntil.get(fileId);
                if (until != null && until > System.currentTimeMillis()) {
                    return readSegments(fileId);
                }
            }
            CloudLiveApi.Segments fetched = api.fetchSegments(fileId);
            if (fetched == null || fetched.items.isEmpty()) {
                synchronized (cacheLock) {
                    segmentFailUntil.put(fileId, System.currentTimeMillis()
                            + SEGMENT_FAIL_BACKOFF_MS);
                }
                return readSegments(fileId);
            }
            writeSegments(fileId, fetched);
            synchronized (cacheLock) {
                segmentMemory.put(fileId, fetched);
                segmentSavedAt.put(fileId, System.currentTimeMillis());
                segmentFailUntil.remove(fileId);
            }
            return fetched;
        }
    }

    void prefetchSegments(final String fileId, final java.util.concurrent.Executor executor) {
        if (cachedSegments(fileId) != null) {
            return;
        }
        synchronized (cacheLock) {
            if (prefetching.contains(fileId)) {
                return;
            }
            prefetching.add(fileId);
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    ensureSegments(fileId);
                } finally {
                    synchronized (cacheLock) {
                        prefetching.remove(fileId);
                    }
                }
            }
        });
    }

    double anchor(String channelId, boolean reset) {
        double now = System.currentTimeMillis() / 1000.0;
        synchronized (cacheLock) {
            if (!reset) {
                Double existing = anchors.get(channelId);
                if (existing != null) {
                    return existing;
                }
            }
            anchors.put(channelId, now);
            writeAnchor(channelId, now);
            return now;
        }
    }

    int refresh(boolean force) throws IOException {
        if (config.parentFileId.length() == 0) {
            throw new IOException("请先在网页管理中填写云盘根目录 ID");
        }
        if (config.authorization.length() == 0) {
            throw new IOException("请先在网页管理中填写云盘授权信息");
        }
        synchronized (refreshLock) {
            List<JSONObject> parentItems = api.listFiles(config.parentFileId);
            if (parentItems == null) {
                throw new IOException("读取云盘根目录失败，授权可能已过期");
            }
            Map<String, String> currentDirs = new LinkedHashMap<String, String>();
            for (JSONObject item : parentItems) {
                if ("folder".equals(item.optString("type", ""))) {
                    currentDirs.put(item.optString("fileId", ""),
                            item.optString("name", ""));
                }
            }
            Map<String, String> oldParent;
            Map<String, Map<String, String>> oldSecond;
            synchronized (cacheLock) {
                oldParent = new LinkedHashMap<String, String>(parentNames);
                oldSecond = new HashMap<String, Map<String, String>>();
                for (Map.Entry<String, Map<String, String>> entry : secondNames.entrySet()) {
                    oldSecond.put(entry.getKey(),
                            new HashMap<String, String>(entry.getValue()));
                }
            }
            for (String removed : oldParent.keySet()) {
                if (!currentDirs.containsKey(removed)) {
                    synchronized (cacheLock) {
                        dirChildren.remove(removed);
                        secondNames.remove(removed);
                    }
                    new File(channelsDir, removed + ".json").delete();
                }
            }
            synchronized (cacheLock) {
                parentNames.clear();
                parentNames.putAll(currentDirs);
            }
            int channelCount = 0;
            for (Map.Entry<String, String> dir : currentDirs.entrySet()) {
                String dirId = dir.getKey();
                String dirName = dir.getValue();
                Map<String, String> oldThird = oldSecond.get(dirId);
                if (oldThird == null) {
                    oldThird = new HashMap<String, String>();
                }
                boolean needSecond = force || !oldParent.containsKey(dirId)
                        || !dirName.equals(oldParent.get(dirId));
                List<DirChild> children;
                if (needSecond) {
                    List<JSONObject> items = api.listFiles(dirId);
                    if (items == null) {
                        // Keep the previous snapshot so a transient failure does
                        // not make this group's channels disappear.
                        children = dirChildren(dirId);
                        if (children == null || children.isEmpty()) {
                            AppLog.w(TAG, "Unable to read directory " + dirName);
                            continue;
                        }
                    } else {
                        children = parseItems(items);
                        Map<String, String> third = new HashMap<String, String>();
                        for (DirChild child : children) {
                            third.put(child.fileId, child.name);
                        }
                        synchronized (cacheLock) {
                            dirChildren.put(dirId, children);
                            secondNames.put(dirId, third);
                        }
                        writeDirChildren(dirId, dirName, children);
                    }
                } else {
                    children = dirChildren(dirId);
                    if (children == null || children.isEmpty()) {
                        continue;
                    }
                }
                for (DirChild child : children) {
                    if (child.fileId.length() == 0) {
                        continue;
                    }
                    if ("video".equals(child.type)) {
                        synchronized (cacheLock) {
                            videoChannels.add(child.fileId);
                            folderChannels.remove(child.fileId);
                        }
                        channelCount++;
                        continue;
                    }
                    boolean needThird = force || !oldThird.containsKey(child.fileId)
                            || !child.name.equals(oldThird.get(child.fileId));
                    if (needThird) {
                        CloudLiveApi.FolderMeta fetched = api.fetchFolderMeta(child.fileId,
                                child.name, 0);
                        if (fetched != null) {
                            synchronized (cacheLock) {
                                folderMeta.put(child.fileId, fetched);
                                folderChannels.add(child.fileId);
                                videoChannels.remove(child.fileId);
                            }
                            writeFolderMeta(child.fileId, fetched);
                        }
                    }
                    CloudLiveApi.FolderMeta meta = folderMeta(child.fileId);
                    if (meta != null && !meta.episodes.isEmpty()) {
                        synchronized (cacheLock) {
                            folderChannels.add(child.fileId);
                        }
                        channelCount++;
                    }
                }
                // Publish after each group so the first launch shows channels
                // progressively instead of waiting for the whole catalog.
                publishPlaylist();
            }
            synchronized (cacheLock) {
                writeNames();
            }
            publishPlaylist();
            AppLog.i(TAG, "Catalog refreshed, channels=" + channelCount);
            return channelCount;
        }
    }

    private void publishPlaylist() {
        synchronized (cacheLock) {
            String text = buildPlaylistLocked();
            playlistText = text;
            writeText(playlistFile, text);
        }
    }

    private String buildPlaylistLocked() {
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U");
        if (config.xTvgUrl.length() > 0) {
            builder.append(" x-tvg-url=\"").append(escape(config.xTvgUrl)).append('"');
        }
        builder.append('\n');
        for (Map.Entry<String, List<DirChild>> entry : dirChildren.entrySet()) {
            String dirId = entry.getKey();
            String group = parentNames.get(dirId);
            if (group == null) {
                group = "";
            }
            List<DirChild> children = entry.getValue();
            if (children == null) {
                continue;
            }
            for (DirChild child : children) {
                if (child.fileId.length() == 0) {
                    continue;
                }
                boolean hasChannels;
                String title;
                if ("video".equals(child.type)) {
                    hasChannels = videoChannels.contains(child.fileId);
                    title = stripExtension(child.name);
                } else {
                    CloudLiveApi.FolderMeta meta = folderMeta.get(child.fileId);
                    hasChannels = meta != null && !meta.episodes.isEmpty();
                    title = child.name;
                }
                if (!hasChannels) {
                    continue;
                }
                builder.append("#EXTINF:-1 tvg-name=\"").append(escape(title))
                        .append("\" tvg-id=\"").append(escape(child.fileId))
                        .append("\" tvg-logo=\"\" group-title=\"").append(escape(group))
                        .append("\",").append(title).append('\n');
                builder.append("http://127.0.0.1:").append(serverPort)
                        .append('/').append(child.fileId).append(".m3u8\n");
            }
        }
        return builder.toString();
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static List<DirChild> parseItems(List<JSONObject> items) {
        List<DirChild> children = new ArrayList<DirChild>();
        for (JSONObject item : items) {
            String type = item.optString("type", "");
            if ("folder".equals(type)) {
                children.add(new DirChild("folder", item.optString("fileId", ""),
                        item.optString("name", "")));
            } else if ("file".equals(type) && CloudLiveApi.isVideo(item)) {
                children.add(new DirChild("video", item.optString("fileId", ""),
                        item.optString("name", "")));
            }
        }
        return children;
    }

    private static List<DirChild> parseDirChildren(JSONArray json) {
        List<DirChild> children = new ArrayList<DirChild>();
        if (json == null) {
            return children;
        }
        for (int i = 0; i < json.length(); i++) {
            JSONObject item = json.optJSONObject(i);
            if (item == null) {
                continue;
            }
            children.add(new DirChild(item.optString("type", "video"),
                    item.optString("file_id", ""), item.optString("name", "")));
        }
        return children;
    }

    private static CloudLiveApi.FolderMeta parseFolderMeta(JSONObject json) {
        if (json == null) {
            return null;
        }
        JSONArray episodesJson = json.optJSONArray("episodes");
        if (episodesJson == null) {
            return null;
        }
        List<CloudLiveApi.Episode> episodes = new ArrayList<CloudLiveApi.Episode>();
        for (int i = 0; i < episodesJson.length(); i++) {
            JSONObject item = episodesJson.optJSONObject(i);
            if (item == null) {
                continue;
            }
            episodes.add(new CloudLiveApi.Episode(item.optString("file_id", ""),
                    item.optString("name", ""), item.optDouble("duration", 0)));
        }
        return new CloudLiveApi.FolderMeta(json.optString("folder_id", ""),
                json.optString("folder_name", ""), episodes);
    }

    private boolean isSegmentExpired(Long savedAt) {
        return savedAt == null || System.currentTimeMillis() - savedAt > SEGMENT_TTL_MS;
    }

    private void ensureDirectories() {
        root.mkdirs();
        segmentsDir.mkdirs();
        channelsDir.mkdirs();
        foldersDir.mkdirs();
        anchorsDir.mkdirs();
    }

    private void writeNames() {
        try {
            JSONObject parent = new JSONObject();
            for (Map.Entry<String, String> entry : parentNames.entrySet()) {
                parent.put(entry.getKey(), entry.getValue());
            }
            JSONObject second = new JSONObject();
            for (Map.Entry<String, Map<String, String>> entry : secondNames.entrySet()) {
                JSONObject third = new JSONObject();
                for (Map.Entry<String, String> child : entry.getValue().entrySet()) {
                    third.put(child.getKey(), child.getValue());
                }
                second.put(entry.getKey(), third);
            }
            writeJson(namesFile, new JSONObject().put("parent", parent).put("second", second));
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to save name snapshot", error);
        }
    }

    private void writeDirChildren(String dirId, String dirName, List<DirChild> children) {
        try {
            JSONArray array = new JSONArray();
            for (DirChild child : children) {
                array.put(new JSONObject().put("type", child.type)
                        .put("file_id", child.fileId).put("name", child.name));
            }
            writeJson(new File(channelsDir, dirId + ".json"), new JSONObject()
                    .put("dir_id", dirId).put("dir_name", dirName).put("children", array));
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to save directory snapshot", error);
        }
    }

    private void writeFolderMeta(String folderId, CloudLiveApi.FolderMeta meta) {
        try {
            JSONArray episodes = new JSONArray();
            for (CloudLiveApi.Episode episode : meta.episodes) {
                episodes.put(new JSONObject().put("file_id", episode.fileId)
                        .put("name", episode.name).put("duration", episode.duration));
            }
            writeJson(new File(foldersDir, folderId + ".json"), new JSONObject()
                    .put("folder_id", folderId).put("folder_name", meta.folderName)
                    .put("episodes", episodes).put("total_duration", meta.totalDuration));
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to save folder metadata", error);
        }
    }

    private void writeSegments(String fileId, CloudLiveApi.Segments segments) {
        try {
            JSONArray items = new JSONArray();
            for (CloudLiveApi.Segment segment : segments.items) {
                items.put(new JSONArray().put(segment.duration).put(segment.url));
            }
            writeJson(new File(segmentsDir, fileId + ".json"), new JSONObject()
                    .put("target_duration", segments.targetDuration).put("segments", items));
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to save segments", error);
        }
    }

    private CloudLiveApi.Segments readSegments(String fileId) {
        JSONObject json = readJson(new File(segmentsDir, fileId + ".json"));
        if (json == null) {
            return null;
        }
        JSONArray items = json.optJSONArray("segments");
        if (items == null) {
            return null;
        }
        List<CloudLiveApi.Segment> segments = new ArrayList<CloudLiveApi.Segment>();
        for (int i = 0; i < items.length(); i++) {
            JSONArray item = items.optJSONArray(i);
            if (item == null || item.length() < 2) {
                continue;
            }
            segments.add(new CloudLiveApi.Segment(item.optDouble(0, 2.0),
                    item.optString(1, "")));
        }
        if (segments.isEmpty()) {
            return null;
        }
        return new CloudLiveApi.Segments(segments,
                Math.max(1, json.optInt("target_duration", 5)));
    }

    private void writeAnchor(String channelId, double t0) {
        try {
            writeJson(new File(anchorsDir, channelId + ".json"),
                    new JSONObject().put("t0", t0));
        } catch (JSONException error) {
            AppLog.w(TAG, "Unable to save anchor", error);
        }
    }

    private JSONObject readJson(File file) {
        String text = readText(file);
        if (text == null) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            return null;
        }
    }

    private void writeJson(File file, JSONObject json) {
        writeText(file, json.toString());
    }

    private String readText(File file) {
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } catch (IOException error) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File temp = new File(file.getPath() + ".tmp");
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(temp);
            output.write(text.getBytes("UTF-8"));
            output.flush();
            output.close();
            output = null;
            if (!temp.renameTo(file)) {
                file.delete();
                temp.renameTo(file);
            }
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to write " + file.getName(), error);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
