package xiao.bu.tv;

/** 一条搜索结果。字段与 channel_row 的 SELECT 列表一一对应。 */
final class ChannelSearchHit {
    final long id;
    /** 稳定键：跨频道增删改保持不变，用来找回频道和保存别名/权重。 */
    final String key;
    final String groupTitle;
    final String channelName;
    final String number;
    final int groupPos;
    final int channelPos;
    final String aliases;
    final double baseWeight;
    final long hot;
    final double bm25;
    final double score;

    private ChannelSearchHit(long id, String key, String groupTitle, String channelName,
            String number, int groupPos, int channelPos, String aliases,
            double baseWeight, long hot, double bm25, double score) {
        this.id = id;
        this.key = key;
        this.groupTitle = groupTitle;
        this.channelName = channelName;
        this.number = number;
        this.groupPos = groupPos;
        this.channelPos = channelPos;
        this.aliases = aliases;
        this.baseWeight = baseWeight;
        this.hot = hot;
        this.bm25 = bm25;
        this.score = score;
    }

    static ChannelSearchHit fromRow(Object[] row) {
        return new ChannelSearchHit(
                asLong(row[0]), SQLiteResult.asString(row[1]), nullToEmpty(row[2]),
                nullToEmpty(row[3]), nullToEmpty(row[4]),
                (int) asLong(row[5]), (int) asLong(row[6]),
                nullToEmpty(row[7]), asDouble(row[8]), asLong(row[9]),
                asDouble(row[10]), asDouble(row[11]));
    }

    private static String nullToEmpty(Object v) {
        String s = SQLiteResult.asString(v);
        return s == null ? "" : s;
    }

    private static long asLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static double asDouble(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0d;
    }
}
