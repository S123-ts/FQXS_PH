-- 「关注」标记：与拉黑并列的第二种行内标记，用来把想持续盯的书留在手边。
--
-- 为什么不复用 is_blocked 取反：拉黑是「排除」，关注是「保留」，两者可以同时成立
-- （既关注又拉黑的书通常不存在，但语义上不该互斥），用一列表达不了三种状态。
-- 按 book_id 全行联动，理由与 is_blocked 相同：同一本书在每个分类榜各有一行，
-- 只在当前分类行上打标记会让「全部」视图和切分类后的状态互相矛盾。
-- 不加索引：筛选与拉黑一样在内存里做（千级数据量），加了也没人用。
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS is_watched BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE novel_rank ADD COLUMN IF NOT EXISTS watched_time TIMESTAMP;
