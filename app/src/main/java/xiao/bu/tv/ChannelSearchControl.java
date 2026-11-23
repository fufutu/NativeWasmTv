package xiao.bu.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索能力的 JSON 门面：手机网页与外部语音调用都走这里，不直接碰 SQL。
 * 表结构、排序策略都在 {@link ChannelSearchIndex} 与 assets/search/schema.sql 里。
 */
final class ChannelSearchControl {

    private ChannelSearchControl() { }

    /** 搜索并返回条目（不切台）。 */
    static JSONObject query(Context context, JSONObject request) throws Exception {
        ChannelSearchIndex index = ChannelSearchIndex.get(context);
        String text = request.optString("text", request.optString("query", "")).trim();
        JSONObject result = new JSONObject().put("ok", true).put("text", text)
                .put("available", index.isAvailable());
        if (!index.isAvailable()) {
            result.put("reason", index.reason()).put("items", new JSONArray()).put("count", 0);
            return result;
        }
        if (text.length() == 0) {
            result.put("items", new JSONArray()).put("count", 0);
            return result;
        }
        List<ChannelSearchHit> hits = index.search(text, request.optInt("limit", 12));
        result.put("items", items(hits)).put("count", hits.size());
        return result;
    }

    /** 把搜索结果转成 TV 候选浮层能用的对象；位置用稳定键重新定位，避免目录刚变过时错位。 */
    static List<VoiceChannelMatcher.Candidate> candidates(Context context, JSONObject request)
            throws Exception {
        ChannelSearchIndex index = ChannelSearchIndex.get(context);
        String text = request.optString("text", request.optString("query", "")).trim();
        if (text.length() == 0) return new ArrayList<VoiceChannelMatcher.Candidate>();
        if (!index.isAvailable()) {
            throw new ControlRequestException(503, index.reason());
        }
        List<ChannelSearchHit> hits = index.search(text, request.optInt("limit", 6));
        List<VoiceChannelMatcher.Candidate> out = new ArrayList<VoiceChannelMatcher.Candidate>();
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        for (ChannelSearchHit hit : hits) {
            VoiceChannelMatcher.Candidate candidate = candidate(index, hit, groups);
            if (candidate != null) out.add(candidate);
        }
        return out;
    }

    /** 按稳定键精确播放（搜索页点某一条时用）。 */
    static VoiceChannelMatcher.Candidate byKey(String key) {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups == null || key == null || key.length() == 0) return null;
        int[] position = ChannelSearchIndex.locate(key);
        if (position == null) return null;
        ChannelCatalog.Group group = groups[position[0]];
        Channel channel = group.channels[position[1]];
        return new VoiceChannelMatcher.Candidate(position[0], position[1],
                group.displayTitle != null && group.displayTitle.length() > 0
                        ? group.displayTitle : group.title,
                channel.name, 1.0d, 0, 0);
    }

    private static VoiceChannelMatcher.Candidate candidate(ChannelSearchIndex index,
            ChannelSearchHit hit, ChannelCatalog.Group[] groups) {
        int[] position = ChannelSearchIndex.locate(hit.key);
        int groupPos = position != null ? position[0] : hit.groupPos;
        int channelPos = position != null ? position[1] : hit.channelPos;
        if (groups == null || groupPos < 0 || groupPos >= groups.length) return null;
        ChannelCatalog.Group group = groups[groupPos];
        if (group == null || group.channels == null || channelPos < 0
                || channelPos >= group.channels.length) return null;
        Channel channel = group.channels[channelPos];
        return new VoiceChannelMatcher.Candidate(groupPos, channelPos,
                group.displayTitle != null && group.displayTitle.length() > 0
                        ? group.displayTitle : (group.title == null ? "" : group.title),
                channel.name, hit.score, 0, (int) Math.min(Integer.MAX_VALUE, hit.hot));
    }

    private static JSONArray items(List<ChannelSearchHit> hits) throws JSONException {
        JSONArray array = new JSONArray();
        for (ChannelSearchHit hit : hits) {
            array.put(new JSONObject()
                    .put("key", hit.key)
                    .put("name", hit.channelName)
                    .put("group", hit.groupTitle)
                    .put("number", hit.number)
                    .put("groupIndex", hit.groupPos)
                    .put("channelIndex", hit.channelPos)
                    .put("aliases", hit.aliases)
                    .put("score", Math.round(hit.score * 1000) / 1000.0d)
                    .put("hot", hit.hot)
                    .put("weight", Math.round(hit.baseWeight * 100) / 100.0d));
        }
        return array;
    }

    /** 管理页请求：list / save / config / status / rebuild。 */
    static JSONObject admin(Context context, JSONObject request) throws Exception {
        ChannelSearchIndex index = ChannelSearchIndex.get(context);
        String action = request.optString("action", "list");
        if ("status".equals(action)) return status(index);
        if (!index.isAvailable()) {
            throw new ControlRequestException(503, index.reason());
        }
        if ("list".equals(action)) return list(index, request);
        if ("save".equals(action)) return save(index, request);
        if ("config".equals(action)) return config(index, request);
        if ("rebuild".equals(action)) {
            index.resync();
            index.refreshHot();
            return status(index).put("rebuilt", true);
        }
        throw new ControlRequestException(400, "未知操作: " + action);
    }

    private static JSONObject status(ChannelSearchIndex index) throws Exception {
        JSONObject object = new JSONObject().put("ok", true)
                .put("available", index.isAvailable())
                .put("engine", SQLiteEngine.isLibraryLoaded());
        if (!index.isAvailable()) {
            return object.put("reason", index.reason()).put("rows", 0).put("groups", 0)
                    .put("items", new JSONArray());
        }
        long total = index.rowTotal();
        JSONArray weights = new JSONArray();
        for (String[] field : index.fieldWeightSnapshot()) {
            weights.put(new JSONObject().put("name", field[0]).put("type", Long.parseLong(field[1]))
                    .put("weight", Double.parseDouble(field[2])));
        }
        JSONObject config = new JSONObject();
        Map<String, String> snapshot = index.configSnapshot();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            config.put(entry.getKey(), entry.getValue());
        }
        return object.put("rows", total).put("groups", groupCount())
                .put("fieldWeights", weights).put("config", config);
    }

    private static int groupCount() {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups == null) return 0;
        int count = 0;
        for (ChannelCatalog.Group group : groups) {
            if (group != null && group.source != ChannelCatalog.SOURCE_FAVORITES) count++;
        }
        return count;
    }

    private static JSONObject list(ChannelSearchIndex index, JSONObject request) throws Exception {
        String query = request.optString("q", "");
        int offset = Math.max(0, request.optInt("offset", 0));
        int limit = request.optInt("limit", 60);
        if (limit <= 0 || limit > 200) limit = 60;
        List<ChannelSearchIndex.Row> rows = index.listRows(query, offset, limit);
        JSONArray items = new JSONArray();
        for (ChannelSearchIndex.Row row : rows) {
            items.put(new JSONObject()
                    .put("key", row.key)
                    .put("name", row.channelName)
                    .put("group", row.groupTitle)
                    .put("number", row.number)
                    .put("aliases", row.aliases)
                    .put("weight", row.baseWeight)
                    .put("hot", row.hot)
                    .put("hotManual", row.hotManual == null ? JSONObject.NULL : row.hotManual)
                    .put("enabled", row.enabled));
        }
        return new JSONObject().put("ok", true).put("items", items).put("offset", offset)
                .put("limit", limit).put("q", query).put("total", index.rowTotal());
    }

    private static JSONObject save(ChannelSearchIndex index, JSONObject request) throws Exception {
        String key = request.optString("key", "");
        if (key.length() == 0) throw new ControlRequestException(400, "缺少频道标识");
        String aliases = request.optString("aliases", "");
        double weight = request.has("weight") ? request.optDouble("weight", 1.0d) : 1.0d;
        Long hotManual = null;
        if (request.has("hotManual") && !request.isNull("hotManual")) {
            hotManual = Long.valueOf(request.optLong("hotManual", 0L));
        }
        boolean enabled = request.optBoolean("enabled", true);
        boolean saved = index.saveRow(key, aliases, weight, hotManual, enabled);
        if (!saved) throw new ControlRequestException(404, "频道已不在目录中，请刷新后重试");
        return new JSONObject().put("ok", true).put("saved", true);
    }

    private static JSONObject config(ChannelSearchIndex index, JSONObject request) throws Exception {
        JSONObject weights = request.optJSONObject("fieldWeights");
        if (weights != null) {
            int type = 1;
            java.util.Iterator<String> names = weights.keys();
            while (names.hasNext()) {
                String name = names.next();
                index.saveFieldWeight(name, type++, weights.optDouble(name, 1.0d));
            }
        }
        boolean autoHot = request.optBoolean("autoHot",
                "1".equals(index.configSnapshot().get("auto_hot")));
        double hotGain = request.optDouble("hotGain", -1.0d);
        if (hotGain < 0) {
            hotGain = parse(index.configSnapshot().get("hot_gain"), 0.02d);
        }
        long hotCap = request.optLong("hotCap", -1L);
        if (hotCap <= 0) {
            hotCap = (long) parse(index.configSnapshot().get("hot_cap"), 200d);
        }
        String mode = request.optString("rankMode", index.configSnapshot().get("rank_mode"));
        if (mode == null || mode.length() == 0) mode = "hybrid";
        index.saveRankConfig(autoHot, hotGain, hotCap, mode);
        if (request.optBoolean("rebuild", false)) index.resync();
        return status(index);
    }

    private static double parse(String text, double fallback) {
        if (text == null) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

}
