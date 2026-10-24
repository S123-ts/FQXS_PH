-- 作者性别与评分：第三方 /info 一次返回（author_info.gender + score）。
-- 第三方线路很小，按「当前榜单热度前 50、每次最多 5 本、串行 2 秒」补，不批量拉。
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS author_gender TINYINT;
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS rating DECIMAL(3,1);
-- 性别只取一次：有值即不再请求（第三方 2=未知也记时间，不再反复试）
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS author_gender_checked_time TIMESTAMP;
-- 评分 10 天刷新一次
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS rating_checked_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_novel_rank_author_gender ON novel_rank (rank_type, author_gender);
