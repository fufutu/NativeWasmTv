package xiao.bu.tv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Minimal client for the personal cloud drive endpoints used by the pseudo-live channels. */
final class CloudLiveApi {
    private static final String TAG = "CloudLive";
    private static final String BASE = "https://personal-kd-njs.yun.139.com";
    private static final String LIST_URL = BASE + "/hcy/file/list";
    private static final String PREVIEW_URL = BASE + "/hcy/videoPreview/getPreviewInfo";
    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");
    private static final int MAX_PAGES = 100;

    static final class Segment {
        final double duration;
        final String url;

        Segment(double duration, String url) {
            this.duration = duration;
            this.url = url;
        }
    }

    static final class Segments {
        final List<Segment> items;
        final int targetDuration;

        Segments(List<Segment> items, int targetDuration) {
            this.items = items;
            this.targetDuration = targetDuration;
        }
    }

    static final class Episode {
        final String fileId;
        final String name;
        final double duration;
        /** Immediate season folder label ("S1", "第二季"), empty for direct videos. */
        final String season;

        Episode(String fileId, String name, double duration) {
            this(fileId, name, duration, "");
        }

        Episode(String fileId, String name, double duration, String season) {
            this.fileId = fileId;
            this.name = name;
            this.duration = duration;
            this.season = season == null ? "" : season;
        }
    }

    static final class FolderMeta {
        final String folderId;
        final String folderName;
        final List<Episode> episodes;
        final double[] offsets;
        final double totalDuration;

        FolderMeta(String folderId, String folderName, List<Episode> episodes) {
            this.folderId = folderId;
            this.folderName = folderName;
            this.episodes = episodes;
            double total = 0;
            offsets = new double[episodes.size()];
            for (int i = 0; i < episodes.size(); i++) {
                offsets[i] = total;
                total += Math.max(0, episodes.get(i).duration);
            }
            totalDuration = total;
        }
    }

    private final CloudLiveConfig config;
    private final OkHttpClient client;

    CloudLiveApi(CloudLiveConfig config) {
        this.config = config;
        client = NetworkClient.sharedClient().newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /** Returns null when the request failed; an empty list means the folder is empty. */
    List<JSONObject> listFiles(String parentFileId) {
        List<JSONObject> items = new ArrayList<JSONObject>();
        String cursor = "";
        int pages = 0;
        while (true) {
            try {
                JSONObject pageInfo = new JSONObject()
                        .put("pageSize", 100)
                        .put("pageCursor", cursor.length() == 0 ? JSONObject.NULL : cursor);
                JSONObject payload = new JSONObject()
                        .put("pageInfo", pageInfo)
                        .put("orderBy", "name")
                        .put("orderDirection", "ASC")
                        .put("parentFileId", parentFileId)
                        .put("imageThumbnailStyleList",
                                new JSONArray().put("Small").put("Large"));
                JSONObject response = postJson(LIST_URL, payload);
                if (response == null || !response.optBoolean("success")) {
                    if (response != null) {
                        AppLog.w(TAG, "file/list rejected: " + response.optString("message"));
                    }
                    return null;
                }
                JSONObject data = response.optJSONObject("data");
                if (data == null) {
                    return null;
                }
                JSONArray page = data.optJSONArray("items");
                if (page != null) {
                    for (int i = 0; i < page.length(); i++) {
                        JSONObject item = page.optJSONObject(i);
                        if (item != null) {
                            items.add(item);
                        }
                    }
                }
                String next = data.isNull("nextPageCursor")
                        ? "" : data.optString("nextPageCursor", "").trim();
                // A JSON null cursor used to arrive as the literal "null" on some
                // Android versions, which made this loop request forever.
                if (next.length() == 0 || "null".equalsIgnoreCase(next) || next.equals(cursor)) {
                    return items;
                }
                cursor = next;
                if (++pages > MAX_PAGES) {
                    AppLog.w(TAG, "file/list stopped after " + MAX_PAGES + " pages");
                    return items;
                }
            } catch (JSONException error) {
                AppLog.w(TAG, "file/list response is invalid", error);
                return null;
            }
        }
    }

    Segments fetchSegments(String fileId) {
        try {
            JSONObject response = postJson(PREVIEW_URL, new JSONObject()
                    .put("fileId", fileId)
                    .put("category", "video"));
            if (response == null || !response.optBoolean("success")) {
                return null;
            }
            JSONObject data = response.optJSONObject("data");
            JSONObject previewInfo = data == null ? null : data.optJSONObject("previewInfo");
            String masterUrl = previewInfo == null ? "" : previewInfo.optString("url", "");
            if (masterUrl.length() == 0) {
                return null;
            }
            String master = getText(masterUrl);
            if (master == null) {
                return null;
            }
            String subUri = "";
            String[] masterLines = splitLines(master);
            for (String line : masterLines) {
                if (line.contains("480/index.m3u8")) {
                    subUri = line;
                    break;
                }
            }
            if (subUri.length() == 0) {
                for (String line : masterLines) {
                    if (!line.startsWith("#") && line.contains("index.m3u8")) {
                        subUri = line;
                        break;
                    }
                }
            }
            if (subUri.length() == 0) {
                return null;
            }
            String subUrl = new URL(new URL(masterUrl), subUri).toString();
            String prefix = subUrl.substring(0, subUrl.lastIndexOf('/') + 1);
            String sub = getText(subUrl);
            if (sub == null) {
                return null;
            }
            List<Segment> segments = new ArrayList<Segment>();
            int targetDuration = 5;
            String[] lines = splitLines(sub);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    targetDuration = parseDuration(line.substring(line.indexOf(':') + 1), 5);
                } else if (line.startsWith("#EXTINF:")) {
                    double duration = parseDuration(line.substring(8).split(",")[0], 2.0);
                    if (i + 1 < lines.length && !lines[i + 1].startsWith("#")) {
                        String uri = lines[i + 1];
                        segments.add(new Segment(duration, uri.startsWith("http")
                                ? uri : prefix + uri));
                        i++;
                    }
                }
            }
            if (segments.isEmpty()) {
                return null;
            }
            return new Segments(segments, Math.max(1, targetDuration));
        } catch (IOException error) {
            AppLog.w(TAG, "Unable to read video segments " + fileId, error);
            return null;
        } catch (JSONException error) {
            AppLog.w(TAG, "Preview response is invalid " + fileId, error);
            return null;
        }
    }

    FolderMeta fetchFolderMeta(String folderId, String folderName, int depth) {
        return fetchFolderMeta(folderId, folderName, depth, "");
    }

    private FolderMeta fetchFolderMeta(String folderId, String folderName, int depth, String season) {
        if (depth > 8) {
            return null;
        }
        if (season == null) {
            season = "";
        }
        List<JSONObject> items = listFiles(folderId);
        if (items == null) {
            return null;
        }
        List<Episode> episodes = new ArrayList<Episode>();
        for (JSONObject item : items) {
            String type = item.optString("type", "");
            if ("folder".equals(type)) {
                // Keep the first-level subfolder as the season label so deeper
                // nesting (e.g. 绝望主妇/S1/EP01/...) still resolves to "S1".
                String childSeason = season.length() == 0 ? item.optString("name", "") : season;
                FolderMeta child = fetchFolderMeta(item.optString("fileId", ""),
                        item.optString("name", ""), depth + 1, childSeason);
                if (child != null) {
                    episodes.addAll(child.episodes);
                }
            } else if ("file".equals(type) && isVideo(item)) {
                JSONObject mediaMeta = item.optJSONObject("mediaMetaInfo");
                double duration = mediaMeta == null ? 0
                        : mediaMeta.optDouble("duration", 0);
                episodes.add(new Episode(item.optString("fileId", ""),
                        item.optString("name", ""), Math.max(0, duration), season));
            }
        }
        return new FolderMeta(folderId, folderName, episodes);
    }

    static boolean isVideo(JSONObject item) {
        if ("video".equals(item.optString("category", ""))) {
            return true;
        }
        String extension = item.optString("fileExtension", "").toLowerCase(java.util.Locale.US);
        return "mp4".equals(extension) || "mkv".equals(extension) || "mov".equals(extension)
                || "avi".equals(extension) || "ts".equals(extension) || "flv".equals(extension);
    }

    private JSONObject postJson(String url, JSONObject payload) {
        RequestBody body = RequestBody.create(JSON, payload.toString());
        Request request = new Request.Builder().url(url).headers(headers()).post(body).build();
        Response response = null;
        try {
            response = client.newCall(request).execute();
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                return null;
            }
            return new JSONObject(responseBody.string());
        } catch (IOException error) {
            if (config.debugLog) {
                AppLog.w(TAG, "Request failed: " + url, error);
            }
            return null;
        } catch (JSONException error) {
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private String getText(String url) {
        Request request = new Request.Builder().url(url).get().build();
        Response response = null;
        try {
            response = client.newCall(request).execute();
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            return body.string();
        } catch (IOException error) {
            return null;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private okhttp3.Headers headers() {
        // Accept-Encoding is intentionally left to OkHttp so gzip is transparent.
        return new okhttp3.Headers.Builder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .add("Authorization", config.authorization)
                .add("CMS-DEVICE", "default")
                .add("Content-Type", "application/json;charset=UTF-8")
                .add("INNER-HCY-ROUTER-HTTPS", "1")
                .add("Origin", "https://yun.139.com")
                .add("Referer", "https://yun.139.com/")
                .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36")
                .add("X-Deviceinfo", "||9|7.18.1|chrome|150.0.0.0|||macos 10.15.7||zh-CN|||")
                .add("caller", "web")
                .add("mcloud-channel", "1000101")
                .add("mcloud-client", "10701")
                .add("mcloud-route", "001")
                .add("mcloud-sign", config.mcloudSign)
                .add("mcloud-version", "7.18.1")
                .add("x-SvcType", "1")
                .add("x-huawei-channelSrc", "10000034")
                .add("x-inner-ntwk", "2")
                .add("x-m4c-caller", "PC")
                .add("x-m4c-src", "10002")
                .add("x-yun-api-version", "v1")
                .add("x-yun-app-channel", "10000034")
                .add("x-yun-channel-source", "10000034")
                .add("x-yun-client-info", "||9|7.18.1|chrome|150.0.0.0|||macos 10.15.7||zh-CN|||"
                        + "dW5kZWZpbmVk||")
                .add("x-yun-module-type", "100")
                .add("x-yun-svc-type", "1")
                .build();
    }

    private static String[] splitLines(String text) {
        String[] raw = text.split("\r?\n");
        List<String> lines = new ArrayList<String>();
        for (String line : raw) {
            String trimmed = line.trim();
            if (trimmed.length() > 0) {
                lines.add(trimmed);
            }
        }
        return lines.toArray(new String[0]);
    }

    private static int parseDuration(String raw, int fallback) {
        try {
            return (int) Float.parseFloat(raw.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static double parseDuration(String raw, double fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
