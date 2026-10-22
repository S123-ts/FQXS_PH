-- 2026-10-22 移除朱雀引擎：判定通道统一收敛到 OpenAI 兼容的三家
-- （智谱 GLM / 百度千帆 ERNIE / 硅基流动 Qwen），朱雀的专用 classify 协议
-- 与「命中即停」的逐章校验语义一起下线。
--
-- 只删引擎注册行，不动历史结论：novel_ai_verdict / novel_ai_verdict_log 里
-- 已有的 zhuque-text 记录保留，详情面板仍能追溯当时的判定来源。
DELETE FROM ai_engine WHERE id = 'zhuque-text';
