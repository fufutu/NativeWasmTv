-- 频道搜索索引库（xiao.bu.tv）
-- 说明：这个库不存频道列表本身，只存“可搜索的索引 + 用户维护的别名/权重/热度”。
-- 频道真实数据永远来自 ChannelCatalog.GROUPS，每次同步时按“稳定键”对齐。
-- 因此新增频道、删除频道、改频道名、重排顺序都只需重新同步，不需要改这里的结构。
-- tokenize='simple' 依赖 libsimple.so（jieba），必须在执行本脚本之前先 loadExtension。

-- 元信息：schema 版本、上次同步时间、目录指纹
CREATE TABLE IF NOT EXISTS search_meta (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL DEFAULT ''
);

-- 索引主表：一行对应一个频道
CREATE TABLE IF NOT EXISTS channel_row (
    id INTEGER PRIMARY KEY,                 -- 同时作为 channel_fts 的 rowid
    ch_key TEXT NOT NULL UNIQUE,            -- 稳定键：分组稳定标识 + 频道稳定标识
    group_key TEXT NOT NULL DEFAULT '',     -- 分组稳定键（播放列表用 sourceId，内置用标题）
    group_title TEXT NOT NULL DEFAULT '',   -- 分组当前显示名（同步时刷新）
    group_pos INTEGER NOT NULL DEFAULT 0,   -- 分组在 GROUPS 里的位置（同步时刷新）
    channel_pos INTEGER NOT NULL DEFAULT 0, -- 频道在分组里的位置（同步时刷新）
    channel_number TEXT NOT NULL DEFAULT '',-- 频道号
    channel_name TEXT NOT NULL DEFAULT '',  -- 频道当前名字（同步时刷新）
    catalog_source INTEGER NOT NULL DEFAULT 0,
    aliases TEXT NOT NULL DEFAULT '',       -- 用户维护的别名，逗号分隔
    base_weight REAL NOT NULL DEFAULT 1.0,  -- 用户手动优先级（1.0 为中性）
    hot INTEGER NOT NULL DEFAULT 0,         -- 自动热度（来自播放/命中次数）
    hot_manual INTEGER DEFAULT NULL,        -- 手动覆盖热度，NULL 表示跟随自动热度
    enabled INTEGER NOT NULL DEFAULT 1,     -- 0 表示不参与搜索
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS channel_row_group ON channel_row(group_key);

-- 全文索引（三列可搜索；加可搜索字段只需改这里 + field_weight_config）
CREATE VIRTUAL TABLE IF NOT EXISTS channel_fts USING fts5(
    group_title,
    channel_name,
    aliases,
    tokenize='simple'
);

-- 字段权重：field_name 与 FTS 列名一致即生效；列数变了按 field_type 兜底
CREATE TABLE IF NOT EXISTS field_weight_config (
    field_type INTEGER PRIMARY KEY,
    field_name TEXT NOT NULL,
    weight REAL NOT NULL DEFAULT 1.0
);
INSERT OR IGNORE INTO field_weight_config(field_type, field_name, weight) VALUES(1,'group_title',1.0);
INSERT OR IGNORE INTO field_weight_config(field_type, field_name, weight) VALUES(2,'channel_name',3.0);
INSERT OR IGNORE INTO field_weight_config(field_type, field_name, weight) VALUES(3,'aliases',2.0);

-- 频道消失时把用户维护的数据留档，频道再回来时可以恢复
CREATE TABLE IF NOT EXISTS alias_archive (
    ch_key TEXT PRIMARY KEY,
    aliases TEXT,
    base_weight REAL,
    hot INTEGER,
    hot_manual INTEGER,
    archived_at INTEGER NOT NULL DEFAULT 0
);

-- 排序策略配置
CREATE TABLE IF NOT EXISTS search_config (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL DEFAULT ''
);
INSERT OR IGNORE INTO search_config(k, v) VALUES('hot_gain', '0.02');
INSERT OR IGNORE INTO search_config(k, v) VALUES('hot_cap', '200');
INSERT OR IGNORE INTO search_config(k, v) VALUES('auto_hot', '1');
INSERT OR IGNORE INTO search_config(k, v) VALUES('rank_mode', 'hybrid');
