package xiao.bu.tv;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 频道搜索索引：把 ChannelCatalog.GROUPS 同步进 SQLite FTS5，并按 bm25 + 手动权重 + 自动热度排序。
 *
 * 关键设计（应对频道频繁增删改）：
 *   1. 每个频道有一个“稳定键” ch_key：分组稳定标识 + 频道稳定标识。
 *      频道改了名字、换了顺序、分组改名，键都不变，所以别名/权重/热度不会丢。
 *   2. 每次搜索前比对“目录指纹”（键 + 名字 + 分组名 + 启停状态的摘要）。
 *      指纹没变就不碰数据库；指纹变了就增量对齐：新增的插入、消失的删除并留档、改名改分组的刷新。
 *   3. 只有可搜索内容变化（增/删/改名/分组名/停用/别名）才重建 FTS 索引；顺序变化只更新位置。
 *   4. 热度是外部注入的（当前接 CloudLiveService 的播放/命中次数），只影响排序不参与分词，
 *      因此热度刷新不需要重建索引。
 */
final class ChannelSearchIndex {

    private static final String TAG = "ChannelSearch";
    private static final String SCHEMA_VERSION = "1";
    /** 热度自动刷新最短间隔（毫秒）。 */
    private static final long HOT_REFRESH_INTERVAL_MS = 10L * 60L * 1000L;
    private static final int CANDIDATE_FACTOR = 10;
    private static final int CANDIDATE_MIN = 100;
    private static final int LIMIT_MIN = 1;
    private static final int LIMIT_MAX = 200;
    private static final String EXTENSION_ENTRY = "sqlite3_simple_init";
    private static final String[] DICT_FILES = {
            "jieba.dict.utf8", "hmm_model.utf8", "idf.utf8", "stop_words.utf8", "user.dict.utf8"
    };
    private static final String[] DICT_POS_FILES = {
            "char_state_tab.utf8", "prob_emit.utf8", "prob_start.utf8", "prob_trans.utf8"
    };

    /** 热度来源：返回某频道当前的播放/命中次数。 */
    interface HotProvider {
        long hot(Channel channel);
    }

    /** 管理页一行（含用户可编辑字段）。 */
    static final class Row {
        long id;
        String key;
        String groupKey;
        String groupTitle;
        String channelName;
        String number;
        int groupPos;
        int channelPos;
        int catalogSource;
        String aliases;
        double baseWeight;
        long hot;
        Long hotManual;
        boolean enabled;
        long updatedAt;
    }

    private static ChannelSearchIndex instance;

    private final Context context;
    private final File rootDir;
    private SQLiteEngine db;
    private String unavailableReason;
    private HotProvider hotProvider;
    private long lastHotRefresh;
    private String[] ftsColumns;

    private ChannelSearchIndex(Context context) {
        this.context = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        this.rootDir = new File(this.context.getFilesDir(), "search");
    }

    /** 首次调用时初始化；失败不抛异常，后续调用方用 isAvailable() 判断。 */
    static synchronized ChannelSearchIndex get(Context context) {
        if (instance == null) {
            ChannelSearchIndex created = new ChannelSearchIndex(context);
            created.init();
            instance = created;
        }
        return instance;
    }

    /** 允许外部（测试或切换数据源）重置单例。 */
    static synchronized void reset() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    synchronized boolean isAvailable() {
        return db != null && unavailableReason == null;
    }

    synchronized String reason() {
        return unavailableReason;
    }

    /** 接热度来源；传 null 表示暂不自动刷热度（用手动覆盖值或 0）。 */
    synchronized void setHotProvider(HotProvider provider) {
        this.hotProvider = provider;
        this.lastHotRefresh = 0L;
    }

    /* ------------------------------------------------------------------ */
    /* 初始化                                                              */
    /* ------------------------------------------------------------------ */

    private void init() {
        try {
            if (!SQLiteEngine.isLibraryLoaded()) {
                unavailableReason = "当前安装包没有内置搜索引擎（非 arm32 变体）";
                return;
            }
            if (!rootDir.isDirectory() && !rootDir.mkdirs()) {
                unavailableReason = "无法创建搜索目录";
                return;
            }
            File extension = resolveExtension();
            if (extension == null) {
                unavailableReason = "找不到分词扩展 libsimple.so";
                return;
            }
            File dict = ensureDictionary();
            if (dict == null) {
                unavailableReason = "分词词典解压失败";
                return;
            }
            db = SQLiteEngine.open(new File(rootDir, "index.db").getAbsolutePath());
            db.loadExtension(extension.getAbsolutePath(), EXTENSION_ENTRY);
            db.query("SELECT jieba_dict(?)", dict.getAbsolutePath() + "/");
            applySchema();
            unavailableReason = null;
        } catch (Throwable error) {
            unavailableReason = "搜索索引初始化失败：" + error;
            Log.w(TAG, "search index init failed", error);
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                    // 关不掉就算了，进程内不再使用
                }
                db = null;
            }
        }
    }

    private void close() {
        if (db != null) {
            try {
                db.close();
            } catch (Throwable ignored) {
                // 退出阶段忽略
            }
            db = null;
        }
    }

    /**
     * 找 libsimple.so。优先用安装后解压出来的原生库目录；
     * 若该目录没有文件（未解压安装），直接从 APK 里取出当前 ABI 的那一份。
     */
    private File resolveExtension() {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir != null) {
            File installed = new File(nativeDir, "libsimple.so");
            if (installed.isFile() && installed.length() > 0) {
                return installed;
            }
        }
        File target = new File(rootDir, "libsimple.so");
        if (target.isFile() && target.length() > 0) {
            return target;
        }
        ZipFile apk = null;
        try {
            apk = new ZipFile(context.getApplicationInfo().sourceDir);
            String entryName = null;
            java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("lib/") && name.endsWith("/libsimple.so")) {
                    entryName = name;
                    break;
                }
            }
            if (entryName == null) {
                return null;
            }
            InputStream in = apk.getInputStream(apk.getEntry(entryName));
            copy(in, target);
            return target;
        } catch (Throwable error) {
            Log.w(TAG, "unable to extract libsimple.so", error);
            return null;
        } finally {
            if (apk != null) {
                try {
                    apk.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 把 assets/search/dict 解压到 files/search/dict（只做一次）。 */
    private File ensureDictionary() {
        File dir = new File(rootDir, "dict");
        File marker = new File(dir, ".ready");
        if (marker.isFile() && marker.length() > 0) {
            return dir;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            File posDir = new File(dir, "pos_dict");
            if (!posDir.isDirectory() && !posDir.mkdirs()) {
                return null;
            }
            for (String name : DICT_FILES) {
                copyAsset("search/dict/" + name, new File(dir, name));
            }
            for (String name : DICT_POS_FILES) {
                copyAsset("search/dict/pos_dict/" + name, new File(posDir, name));
            }
            OutputStream out = new FileOutputStream(marker);
            try {
                out.write('1');
            } finally {
                out.close();
            }
            return dir;
        } catch (Throwable error) {
            Log.w(TAG, "unable to unpack jieba dict", error);
            return null;
        }
    }

    private void copyAsset(String assetPath, File target) throws IOException {
        InputStream in = context.getAssets().open(assetPath);
        try {
            copy(in, target);
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } finally {
            out.close();
        }
    }

    private void applySchema() throws IOException {
        String sql = readAsset("search/schema.sql");
        db.exec(sql);
        String stored = asText(db.queryScalar("SELECT v FROM search_meta WHERE k = ?", "schema"));
        if (SCHEMA_VERSION.equals(stored)) {
            return;
        }
        // 结构升级：索引数据可以完全从目录重建，所以直接清空重来
        db.exec("DROP TABLE IF EXISTS channel_fts; DROP TABLE IF EXISTS channel_row;"
                + "DROP TABLE IF EXISTS field_weight_config; DROP TABLE IF EXISTS alias_archive;"
                + "DROP TABLE IF EXISTS search_meta; DROP TABLE IF EXISTS search_config;");
        db.exec(sql);
        db.query("INSERT OR REPLACE INTO search_meta(k, v) VALUES(?, ?)",
                "schema", SCHEMA_VERSION);
    }

    private String readAsset(String path) throws IOException {
        InputStream in = context.getAssets().open(path);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 稳定键                                                              */
    /* ------------------------------------------------------------------ */

    static String groupKey(ChannelCatalog.Group group) {
        if (group == null) return "";
        if (group.source == ChannelCatalog.SOURCE_CUSTOM && group.playlistSourceId != null
                && group.playlistSourceId.length() > 0) {
            return "ps:" + group.playlistSourceId;
        }
        String title = group.title == null ? "" : group.title;
        return "t:" + title;
    }

    static String channelKey(ChannelCatalog.Group group, Channel channel) {
        if (channel == null) return "";
        String identity;
        if (channel.streamId != null && channel.streamId.length() > 0) {
            identity = "sid:" + channel.streamId;
        } else {
            String url = channel.url;
            if (url == null || url.length() == 0) {
                url = channel.urls != null && channel.urls.length > 0 ? channel.urls[0] : null;
            }
            if (url != null && url.length() > 0) {
                identity = "u:" + digest(url);
            } else {
                identity = "n:" + safe(channel.number) + ":" + safe(channel.name);
            }
        }
        return groupKey(group) + "|" + identity;
    }

    private static String digest(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception error) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /* ------------------------------------------------------------------ */
    /* 同步                                                                */
    /* ------------------------------------------------------------------ */

    private static final class Entry {
        String key;
        String groupKey;
        String groupTitle;
        int groupPos;
        int channelPos;
        String number;
        String name;
        int source;
        long hot;
    }

    /** 供搜索与页面调用：目录没变就几乎不花时间。 */
    synchronized void ensureSynced() {
        if (!isAvailable()) return;
        try {
            sync(false);
        } catch (Throwable error) {
            Log.w(TAG, "index sync failed", error);
        }
    }

    /** 强制全量对齐（管理页“重建索引”用）。 */
    synchronized void resync() {
        if (!isAvailable()) return;
        try {
            sync(true);
        } catch (Throwable error) {
            Log.w(TAG, "index resync failed", error);
        }
    }

    private void sync(boolean force) {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        List<Entry> desired = collect(groups);
        String stamp = stamp(desired);
        String stored = asText(db.queryScalar("SELECT v FROM search_meta WHERE k = ?", "catalog_stamp"));
        if (!force && stamp.equals(stored) && rowCount() == desired.size()) {
            maybeRefreshHot(groups);
            return;
        }

        boolean ftsDirty = false;
        long now = System.currentTimeMillis();
        boolean autoHot = boolConfig("auto_hot", true);
        Map<String, long[]> existingIds = new HashMap<String, long[]>();
        Map<String, String[]> existingText = new HashMap<String, String[]>();
        SQLiteResult rows = db.query("SELECT id, ch_key, group_title, channel_name,"
                + " group_pos, channel_pos, channel_number, catalog_source FROM channel_row");
        for (int i = 0; i < rows.rowCount(); i++) {
            String key = rows.getString(i, "ch_key");
            existingIds.put(key, new long[] {rows.getLong(i, "id")});
            existingText.put(key, new String[] {
                    rows.getString(i, "group_title"), rows.getString(i, "channel_name"),
                    rows.getString(i, "channel_number"),
                    String.valueOf(rows.getLong(i, "group_pos")),
                    String.valueOf(rows.getLong(i, "channel_pos")),
                    String.valueOf(rows.getLong(i, "catalog_source"))
            });
        }

        Map<String, String[]> archive = new HashMap<String, String[]>();
        SQLiteResult archived = db.query("SELECT ch_key, aliases, base_weight, hot_manual FROM alias_archive");
        for (int i = 0; i < archived.rowCount(); i++) {
            archive.put(archived.getString(i, "ch_key"), new String[] {
                    asText(archived.get(i, "aliases")),
                    asText(archived.get(i, "base_weight")),
                    asText(archived.get(i, "hot_manual"))
            });
        }

        Set<String> alive = new HashSet<String>();
        db.exec("BEGIN IMMEDIATE");
        try {
            for (Entry entry : desired) {
                alive.add(entry.key);
                long[] existing = existingIds.get(entry.key);
                if (existing == null) {
                    insert(entry, now, autoHot, archive.get(entry.key));
                    ftsDirty = true;
                    continue;
                }
                String[] old = existingText.get(entry.key);
                boolean textChanged = old == null
                        || !safe(entry.groupTitle).equals(safe(old[0]))
                        || !safe(entry.name).equals(safe(old[1]))
                        || !safe(entry.number).equals(safe(old[2]));
                boolean posChanged = old == null
                        || !String.valueOf(entry.groupPos).equals(old[3])
                        || !String.valueOf(entry.channelPos).equals(old[4])
                        || !String.valueOf(entry.source).equals(old[5]);
                if (textChanged || posChanged) {
                    db.query("UPDATE channel_row SET group_key = ?, group_title = ?,"
                            + " group_pos = ?, channel_pos = ?, channel_number = ?,"
                            + " channel_name = ?, catalog_source = ? WHERE ch_key = ?",
                            entry.groupKey, safe(entry.groupTitle), entry.groupPos,
                            entry.channelPos, safe(entry.number), safe(entry.name),
                            entry.source, entry.key);
                }
                if (textChanged) ftsDirty = true;
                if (autoHot && entry.hot > 0) {
                    db.query("UPDATE channel_row SET hot = ? WHERE ch_key = ? AND hot <> ?",
                            entry.hot, entry.key, entry.hot);
                }
            }
            for (String key : existingIds.keySet()) {
                if (alive.contains(key)) continue;
                db.query("INSERT OR REPLACE INTO alias_archive(ch_key, aliases, base_weight,"
                        + " hot, hot_manual, archived_at)"
                        + " SELECT ch_key, aliases, base_weight, hot, hot_manual, ?"
                        + " FROM channel_row WHERE ch_key = ?", now, key);
                db.query("DELETE FROM channel_row WHERE ch_key = ?", key);
                ftsDirty = true;
            }
            if (ftsDirty) rebuildFts();
            db.query("INSERT OR REPLACE INTO search_meta(k, v) VALUES(?, ?)", "catalog_stamp", stamp);
            db.query("INSERT OR REPLACE INTO search_meta(k, v) VALUES(?, ?)", "synced_at",
                    String.valueOf(now));
            db.exec("COMMIT");
        } catch (Throwable error) {
            try {
                db.exec("ROLLBACK");
            } catch (Throwable ignored) {
                // 回滚失败则放弃本次同步
            }
            throw new IllegalStateException("同步失败：" + error, error);
        }
        lastHotRefresh = now;
        Log.i(TAG, "index synced: " + desired.size() + " channels"
                + (ftsDirty ? " (fts rebuilt)" : ""));
    }

    private void insert(Entry entry, long now, boolean autoHot, String[] restore) {
        String aliases = restore == null ? "" : safe(restore[0]);
        double weight = restore == null || restore[1] == null ? 1.0d : parse(restore[1], 1.0d);
        String hotManual = restore == null ? null : restore[2];
        db.query("INSERT INTO channel_row(ch_key, group_key, group_title, group_pos, channel_pos,"
                + " channel_number, channel_name, catalog_source, aliases, base_weight, hot,"
                + " hot_manual, enabled, updated_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1,?)",
                entry.key, entry.groupKey, safe(entry.groupTitle), entry.groupPos,
                entry.channelPos, safe(entry.number), safe(entry.name), entry.source,
                aliases, weight, autoHot ? entry.hot : 0L,
                hotManual == null ? null : Long.valueOf(hotManual), now);
        if (restore != null) {
            db.query("DELETE FROM alias_archive WHERE ch_key = ?", entry.key);
        }
    }

    private List<Entry> collect(ChannelCatalog.Group[] groups) {
        List<Entry> out = new ArrayList<Entry>();
        if (groups == null) return out;
        for (int g = 0; g < groups.length; g++) {
            ChannelCatalog.Group group = groups[g];
            if (group == null || group.channels == null) continue;
            // 收藏是其它分组频道的镜像，索引进来会产生重复条目
            if (group.source == ChannelCatalog.SOURCE_FAVORITES) continue;
            String groupKey = groupKey(group);
            String groupTitle = group.displayTitle != null && group.displayTitle.length() > 0
                    ? group.displayTitle : safe(group.title);
            for (int c = 0; c < group.channels.length; c++) {
                Channel channel = group.channels[c];
                if (channel == null || channel.name == null || channel.name.length() == 0) continue;
                Entry entry = new Entry();
                entry.key = channelKey(group, channel);
                entry.groupKey = groupKey;
                entry.groupTitle = groupTitle;
                entry.groupPos = g;
                entry.channelPos = c;
                entry.number = safe(channel.number);
                entry.name = channel.name;
                entry.source = channel.catalogSource;
                entry.hot = hotProvider == null ? 0L : Math.max(0L, hotProvider.hot(channel));
                out.add(entry);
            }
        }
        return out;
    }

    private String stamp(List<Entry> entries) {
        StringBuilder sb = new StringBuilder(entries.size() * 24);
        for (Entry entry : entries) {
            sb.append(entry.key).append('\u0001').append(entry.groupTitle).append('\u0001')
                    .append(entry.name).append('\u0001').append(entry.number).append('\u0002');
        }
        return digest(sb.toString());
    }

    private void maybeRefreshHot(ChannelCatalog.Group[] groups) {
        if (!boolConfig("auto_hot", true) || hotProvider == null) return;
        long now = System.currentTimeMillis();
        if (now - lastHotRefresh < HOT_REFRESH_INTERVAL_MS) return;
        updateHot(groups, now);
    }

    /** 只刷新热度，不动索引。 */
    synchronized void refreshHot() {
        if (!isAvailable()) return;
        updateHot(ChannelCatalog.GROUPS, System.currentTimeMillis());
    }

    private void updateHot(ChannelCatalog.Group[] groups, long now) {
        List<Entry> entries = collect(groups);
        db.exec("BEGIN IMMEDIATE");
        try {
            for (Entry entry : entries) {
                db.query("UPDATE channel_row SET hot = ? WHERE ch_key = ? AND hot <> ?",
                        entry.hot, entry.key, entry.hot);
            }
            db.exec("COMMIT");
        } catch (Throwable error) {
            try {
                db.exec("ROLLBACK");
            } catch (Throwable ignored) {
                // 忽略
            }
            Log.w(TAG, "hot refresh failed", error);
        }
        lastHotRefresh = now;
    }

    private void rebuildFts() {
        db.exec("DELETE FROM channel_fts");
        db.exec("INSERT INTO channel_fts(rowid, group_title, channel_name, aliases)"
                + " SELECT id, group_title, channel_name, aliases FROM channel_row WHERE enabled = 1");
    }

    private long rowCount() {
        Object value = db.queryScalar("SELECT COUNT(*) FROM channel_row");
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /* ------------------------------------------------------------------ */
    /* 搜索                                                                */
    /* ------------------------------------------------------------------ */

    synchronized List<ChannelSearchHit> search(String keywords, int limit) {
        List<ChannelSearchHit> out = new ArrayList<ChannelSearchHit>();
        if (!isAvailable()) return out;
        String keyword = keywords == null ? "" : keywords.trim();
        if (keyword.length() == 0) return out;
        ensureSynced();
        limit = clamp(limit, LIMIT_MIN, LIMIT_MAX);

        String andQuery = buildQuery(keyword);
        if (andQuery == null || andQuery.length() == 0) return out;
        String orQuery = andQuery.replace(" AND ", " OR ");
        double[] weights = loadFieldWeights();
        String hotExpression = hotExpression();

        runFts(andQuery, weights, hotExpression, limit, false, out);
        if (out.size() < limit && !orQuery.equals(andQuery)) {
            runFts(orQuery, weights, hotExpression, limit, true, out);
        }
        if (out.size() < limit) {
            fallbackScan(keyword, hotExpression, limit, out);
        }
        return out;
    }

    /**
     * 兜底扫描：分词会把 “CCTV-6” 切成 cctv / 6，用户敲 “CCTV6” 就搜不到了。
     * 这里去掉空格和连字符后做子串匹配，只在前面的全文检索没凑够结果时执行。
     */
    private void fallbackScan(String keyword, String hotExpression, int limit,
            List<ChannelSearchHit> out) {
        String compact = compact(keyword);
        if (compact.length() == 0) return;
        int need = limit;
        SQLiteResult scan = db.query("SELECT c.id, c.ch_key, c.group_title, c.channel_name,"
                + " c.channel_number, c.group_pos, c.channel_pos, c.aliases, c.base_weight,"
                + " COALESCE(c.hot_manual, c.hot) AS hot, 0.0 AS bm25_score,"
                + " 0.35 * c.base_weight * " + hotExpression + " AS final_score"
                + " FROM channel_row c WHERE c.enabled = 1 AND ("
                + " instr(lower(replace(replace(c.channel_name,'-',''),' ','')), ?) > 0"
                + " OR instr(lower(replace(replace(c.aliases,'-',''),' ','')), ?) > 0"
                + " OR instr(lower(c.group_title), ?) > 0)"
                + " ORDER BY final_score DESC LIMIT ?", compact, compact, compact, need);
        for (int i = 0; i < scan.rowCount(); i++) {
            ChannelSearchHit hit = ChannelSearchHit.fromRow(scan.row(i));
            if (seen(out, hit.key)) continue;
            out.add(hit);
            if (out.size() >= limit) break;
        }
    }

    /** 去掉空格、连字符、下划线和全角空格，转小写，用于兜底子串匹配。 */
    private static String compact(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '-' || ch == '_' || ch == '\u3000'
                    || ch == '\u00a0' || ch == '\u2013' || ch == '\u2014') {
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }

    /** 用扩展的 jieba 分词构造 AND 查询；扩展不可用时按空白切词。 */
    private String buildQuery(String keyword) {
        String query = null;
        try {
            query = asText(db.queryScalar("SELECT jieba_query(?)", keyword));
        } catch (Throwable error) {
            query = null;
        }
        if (query != null && query.trim().length() > 0) return query.trim();
        StringBuilder sb = new StringBuilder();
        for (String token : keyword.split("[ \\t\\r\\n]+")) {
            if (token.length() == 0) continue;
            if (sb.length() > 0) sb.append(" AND ");
            sb.append('"').append(token.replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    private String[] ftsColumnNames() {
        if (ftsColumns != null) return ftsColumns;
        SQLiteResult info = db.query("PRAGMA table_info(channel_fts)");
        if (info.rowCount() == 0) {
            throw new IllegalStateException("读不到 FTS 表定义（schema.sql 没执行？）");
        }
        String[] cols = new String[info.rowCount()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = info.getString(i, "name");
        }
        ftsColumns = cols;
        return cols;
    }

    /** 每个 FTS 列一个 bm25 权重；配置按列名匹配，匹配不上用 field_type 兜底。 */
    private double[] loadFieldWeights() {
        String[] cols = ftsColumnNames();
        double[] weights = new double[cols.length];
        for (int i = 0; i < weights.length; i++) weights[i] = 1.0d;
        SQLiteResult config = db.query("SELECT field_type, field_name, weight FROM field_weight_config");
        for (int i = 0; i < config.rowCount(); i++) {
            int target = -1;
            String name = config.getString(i, "field_name");
            for (int c = 0; c < cols.length; c++) {
                if (cols[c].equals(name)) {
                    target = c;
                    break;
                }
            }
            if (target < 0) {
                long type = config.getLong(i, "field_type");
                if (type >= 1 && type <= cols.length) target = (int) (type - 1);
            }
            if (target >= 0) {
                // FTS5 的 bm25 权重必须为正，配置里填 0 时按最小值处理
                double value = config.getDouble(i, "weight");
                weights[target] = value > 0 ? value : 0.01d;
            }
        }
        return weights;
    }

    /**
     * 热度项：最终得分 = bm25 * 手动权重 * 热度项。
     * auto_hot 关掉时热度项为 1；rank_mode=bm25 时同样不参与。
     */
    private String hotExpression() {
        boolean autoHot = boolConfig("auto_hot", true);
        String mode = textConfig("rank_mode", "hybrid");
        if (!autoHot || "bm25".equals(mode)) return "1.0";
        double gain = numberConfig("hot_gain", 0.02d);
        long cap = (long) numberConfig("hot_cap", 200d);
        if (cap < 1) cap = 1;
        return String.format(Locale.US, "(1.0 + %.6f * MIN(COALESCE(c.hot_manual, c.hot, 0), %d))",
                Math.max(0.0d, gain), cap);
    }

    private String buildSql(int weightCount, String hotExpression) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("WITH matched AS (SELECT rowid, -bm25(channel_fts, ");
        for (int i = 0; i < weightCount; i++) {
            if (i > 0) sb.append(", ");
            sb.append('?').append(i + 1);
        }
        sb.append(") AS bm25_score FROM channel_fts WHERE channel_fts MATCH ?")
                .append(weightCount + 1)
                .append(" ORDER BY bm25_score DESC LIMIT ?").append(weightCount + 2)
                .append(") SELECT c.id, c.ch_key, c.group_title, c.channel_name, c.channel_number,")
                .append(" c.group_pos, c.channel_pos, c.aliases, c.base_weight,")
                .append(" COALESCE(c.hot_manual, c.hot) AS hot, m.bm25_score,")
                .append(" m.bm25_score * c.base_weight * ").append(hotExpression)
                .append(" AS final_score FROM matched m JOIN channel_row c ON c.id = m.rowid ");
        if ("hot".equals(textConfig("rank_mode", "hybrid"))) {
            sb.append("ORDER BY COALESCE(c.hot_manual, c.hot) DESC, final_score DESC");
        } else {
            sb.append("ORDER BY final_score DESC");
        }
        sb.append(" LIMIT ?").append(weightCount + 3);
        return sb.toString();
    }

    private void runFts(String matchQuery, double[] weights, String hotExpression, int limit,
            boolean merge, List<ChannelSearchHit> out) {
        int candidateLimit = Math.max(limit * CANDIDATE_FACTOR, CANDIDATE_MIN);
        Object[] args = new Object[weights.length + 3];
        for (int i = 0; i < weights.length; i++) args[i] = Double.valueOf(weights[i]);
        args[weights.length] = matchQuery;
        args[weights.length + 1] = Long.valueOf(candidateLimit);
        args[weights.length + 2] = Long.valueOf(merge ? limit + out.size() : limit);

        SQLiteResult result = db.query(buildSql(weights.length, hotExpression), args);
        for (int i = 0; i < result.rowCount(); i++) {
            ChannelSearchHit hit = ChannelSearchHit.fromRow(result.row(i));
            if (merge && seen(out, hit.key)) continue;
            out.add(hit);
            if (merge && out.size() >= limit) break;
        }
    }

    private static boolean seen(List<ChannelSearchHit> out, String key) {
        if (key == null) return false;
        for (ChannelSearchHit hit : out) {
            if (key.equals(hit.key)) return true;
        }
        return false;
    }

    /**
     * 用稳定键找回频道在 GROUPS 里的位置。目录刚变化过时，命中结果里的位置可能已经过期，
     * 所以切台前要用这里定位。
     */
    static int[] locate(String key) {
        ChannelCatalog.Group[] groups = ChannelCatalog.GROUPS;
        if (groups == null || key == null) return null;
        for (int g = 0; g < groups.length; g++) {
            ChannelCatalog.Group group = groups[g];
            if (group == null || group.channels == null) continue;
            for (int c = 0; c < group.channels.length; c++) {
                if (key.equals(channelKey(group, group.channels[c]))) return new int[] {g, c};
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 管理页数据访问                                                       */
    /* ------------------------------------------------------------------ */

    synchronized long rowTotal() {
        if (!isAvailable()) return 0L;
        return rowCount();
    }

    synchronized List<Row> listRows(String query, int offset, int limit) {
        List<Row> out = new ArrayList<Row>();
        if (!isAvailable()) return out;
        ensureSynced();
        String filter = query == null ? "" : query.trim();
        SQLiteResult result;
        if (filter.length() == 0) {
            result = db.query("SELECT id, ch_key, group_key, group_title, channel_name,"
                    + " channel_number, group_pos, channel_pos, catalog_source, aliases,"
                    + " base_weight, hot, hot_manual, enabled, updated_at FROM channel_row"
                    + " ORDER BY group_pos, channel_pos LIMIT ? OFFSET ?", limit, offset);
        } else {
            String like = filter.toLowerCase(Locale.US);
            result = db.query("SELECT id, ch_key, group_key, group_title, channel_name,"
                    + " channel_number, group_pos, channel_pos, catalog_source, aliases,"
                    + " base_weight, hot, hot_manual, enabled, updated_at FROM channel_row"
                    + " WHERE instr(lower(channel_name), ?) > 0 OR instr(lower(aliases), ?) > 0"
                    + " OR instr(lower(group_title), ?) > 0"
                    + " ORDER BY group_pos, channel_pos LIMIT ? OFFSET ?",
                    like, like, like, limit, offset);
        }
        for (int i = 0; i < result.rowCount(); i++) out.add(row(result, i));
        return out;
    }

    private static Row row(SQLiteResult result, int index) {
        Row row = new Row();
        row.id = result.getLong(index, "id");
        row.key = result.getString(index, "ch_key");
        row.groupKey = result.getString(index, "group_key");
        row.groupTitle = result.getString(index, "group_title");
        row.channelName = result.getString(index, "channel_name");
        row.number = result.getString(index, "channel_number");
        row.groupPos = (int) result.getLong(index, "group_pos");
        row.channelPos = (int) result.getLong(index, "channel_pos");
        row.catalogSource = (int) result.getLong(index, "catalog_source");
        row.aliases = result.getString(index, "aliases");
        row.baseWeight = result.getDouble(index, "base_weight");
        row.hot = result.getLong(index, "hot");
        Object manual = result.get(index, "hot_manual");
        row.hotManual = manual instanceof Number ? Long.valueOf(((Number) manual).longValue()) : null;
        row.enabled = result.getLong(index, "enabled") != 0L;
        row.updatedAt = result.getLong(index, "updated_at");
        return row;
    }

    synchronized boolean saveRow(String key, String aliases, double baseWeight,
            Long hotManual, boolean enabled) {
        if (!isAvailable() || key == null) return false;
        db.exec("BEGIN IMMEDIATE");
        try {
            Object exists = db.queryScalar("SELECT COUNT(*) FROM channel_row WHERE ch_key = ?", key);
            if (!(exists instanceof Number) || ((Number) exists).longValue() == 0L) {
                db.exec("COMMIT");
                return false;
            }
            db.query("UPDATE channel_row SET aliases = ?, base_weight = ?,"
                    + " hot_manual = ?, enabled = ?, updated_at = ? WHERE ch_key = ?",
                    aliases == null ? "" : aliases.trim(),
                    baseWeight <= 0 ? 1.0d : baseWeight,
                    hotManual, enabled ? 1L : 0L, System.currentTimeMillis(), key);
            reindexOne(key);
            db.exec("COMMIT");
            return true;
        } catch (Throwable error) {
            try {
                db.exec("ROLLBACK");
            } catch (Throwable ignored) {
                // 忽略
            }
            throw new IllegalStateException("保存失败：" + error, error);
        }
    }

    /** 单行重建 FTS 条目（别名/启停变化后调用）。 */
    private void reindexOne(String key) {
        SQLiteResult one = db.query("SELECT id, group_title, channel_name, aliases, enabled"
                + " FROM channel_row WHERE ch_key = ?", key);
        if (one.rowCount() == 0) return;
        long id = one.getLong(0, "id");
        db.query("DELETE FROM channel_fts WHERE rowid = ?", id);
        if (one.getLong(0, "enabled") != 0L) {
            db.query("INSERT INTO channel_fts(rowid, group_title, channel_name, aliases)"
                    + " VALUES(?,?,?,?)", id, one.getString(0, "group_title"),
                    one.getString(0, "channel_name"), one.getString(0, "aliases"));
        }
    }

    synchronized void saveFieldWeight(String fieldName, int fieldType, double weight) {
        if (!isAvailable()) return;
        Object existing = db.queryScalar("SELECT field_type FROM field_weight_config"
                + " WHERE field_name = ?", fieldName);
        if (existing instanceof Number) {
            db.query("UPDATE field_weight_config SET weight = ? WHERE field_name = ?",
                    weight, fieldName);
        } else {
            db.query("INSERT OR REPLACE INTO field_weight_config(field_type, field_name, weight)"
                    + " VALUES(?,?,?)", fieldType, fieldName, weight);
        }
    }

    synchronized void saveRankConfig(boolean autoHot, double hotGain, long hotCap, String mode) {
        if (!isAvailable()) return;
        putConfig("auto_hot", autoHot ? "1" : "0");
        putConfig("hot_gain", String.valueOf(hotGain));
        putConfig("hot_cap", String.valueOf(hotCap));
        putConfig("rank_mode", mode == null ? "hybrid" : mode);
    }

    private void putConfig(String key, String value) {
        db.query("INSERT OR REPLACE INTO search_config(k, v) VALUES(?,?)", key, value);
    }

    synchronized Map<String, String> configSnapshot() {
        Map<String, String> out = new HashMap<String, String>();
        if (!isAvailable()) return out;
        SQLiteResult result = db.query("SELECT k, v FROM search_config");
        for (int i = 0; i < result.rowCount(); i++) {
            out.put(result.getString(i, "k"), result.getString(i, "v"));
        }
        return out;
    }

    synchronized List<String[]> fieldWeightSnapshot() {
        List<String[]> out = new ArrayList<String[]>();
        if (!isAvailable()) return out;
        SQLiteResult result = db.query("SELECT field_type, field_name, weight"
                + " FROM field_weight_config ORDER BY field_type");
        for (int i = 0; i < result.rowCount(); i++) {
            out.add(new String[] {
                    result.getString(i, "field_name"),
                    String.valueOf(result.getLong(i, "field_type")),
                    String.valueOf(result.getDouble(i, "weight"))
            });
        }
        return out;
    }

    private boolean boolConfig(String key, boolean fallback) {
        String value = textConfig(key, fallback ? "1" : "0");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private double numberConfig(String key, double fallback) {
        return parse(textConfig(key, String.valueOf(fallback)), fallback);
    }

    private String textConfig(String key, String fallback) {
        try {
            String value = asText(db.queryScalar("SELECT v FROM search_config WHERE k = ?", key));
            return value == null || value.length() == 0 ? fallback : value;
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static double parse(String text, double fallback) {
        if (text == null) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String asText(Object value) {
        return SQLiteResult.asString(value);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
