-- 流水记「这一轮实际看了几章」：详情面板的加权平均 AI 率按章数做权重，
-- 逐章校验 1 章 = 1 份证据，整本初筛按实际采用的章数计。
--
-- 为什么不从 sample_span 反解：该列宽 40，超长会被写入端截断（尾部加「…」），
-- 解析出来的章数只会偏小；逐章校验行也不写 span（沿用明细里的档位），无从解析。
--
-- 逐章校验记 1，整本初筛/重跑记实际采用章数，progress 与 failed 记 0（不计入权重）。
ALTER TABLE novel_ai_verdict_log ADD COLUMN IF NOT EXISTS sample_chapters INT;
