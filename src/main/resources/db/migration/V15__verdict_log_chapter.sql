-- 逐章校验要能回答「这一轮查的是第几章」。
--
-- chapters_checked 只是该型号的进度计数，不是章号：取样档位是 recent（第 990–999 章）
-- 或 random（第 714–718 + 911–915 章）时，进度 3 与章号 3 毫无关系，
-- 页面上的「已查章 3」看不出查的是哪一章。chapter_no 记本次实际校验的章号（1-based）。
--
-- 整本初筛 / 手点重跑没有单章概念，保持 NULL（它们的取样跨度在 sample_span 里）。
ALTER TABLE novel_ai_verdict_log ADD COLUMN IF NOT EXISTS chapter_no INT;
