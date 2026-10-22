-- 取样档位与范围：同一本书可能一次是会员全文抽查、一次只有前 10 章可读区，
-- 不记下来跨档分数不可比。V1 已固化，列变更只能走新迁移。
ALTER TABLE novel_ai_verdict ADD COLUMN IF NOT EXISTS sample_mode VARCHAR(24);
ALTER TABLE novel_ai_verdict ADD COLUMN IF NOT EXISTS sample_span VARCHAR(40);
