-- “全部”视图的字数筛选要下推到 SQL：word_count 是「123.4万」这类字符串，
-- 直接比较大小会得到字典序，因此在写入时同步落一列数值。
-- 历史行的回填在 StartupDataInitializer（解析规则与 NovelTextParser 保持一致）。
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS word_count_wan DECIMAL(10,2);

-- 筛选恒带 rank_type + category_code，字数区间作为附加条件
CREATE INDEX IF NOT EXISTS idx_novel_rank_word ON novel_rank (rank_type, category_code, word_count_wan);
