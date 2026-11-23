package xiao.bu.tv;

/**
 * 统一的频道目标：语音切台与网页搜索共用同一个结果载体。
 * <p>
 * 原来语音侧用自己的 Candidate 类型，网页侧借用了它；现在两者都收敛到这里，
 * 语音与网页拿到的就是同一份解析结果（同一个 key、同一个排序）。
 */
final class ChannelTarget {

    /** 稳定键 ch_key：跨频道增删改不变，用来回查频道。 */
    final String key;
    /** 频道在 ChannelCatalog.GROUPS 里的位置（切换时用）。 */
    final int group;
    final int channel;
    final String groupName;
    final String channelName;
    /** 索引给出的排序分（bm25 × 权重 × 热度因子）。 */
    final double score;
    /** 热度（播放/命中次数），给页面展示与调试用。 */
    final int hits;
    /** 起始集数偏移；-1 表示不指定（从频道当前位置开始）。 */
    final int episodeOffset;

    ChannelTarget(String key, int group, int channel, String groupName, String channelName,
            double score, int hits, int episodeOffset) {
        this.key = key;
        this.group = group;
        this.channel = channel;
        this.groupName = groupName == null ? "" : groupName;
        this.channelName = channelName == null ? "" : channelName;
        this.score = score;
        this.hits = hits;
        this.episodeOffset = episodeOffset;
    }

    /** 复制一份并指定起始集数（"西游记第三集"这类语音说法用）。 */
    ChannelTarget withEpisodeOffset(int episodeOffset) {
        return new ChannelTarget(key, group, channel, groupName, channelName, score, hits,
                episodeOffset);
    }
}
