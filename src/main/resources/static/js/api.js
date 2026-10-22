// @ts-check
/* ============================================================
   js/api.js - API 请求层
   失败时优先使用后端 {code,message} 里的文案，HTTP 状态码兜底
   ============================================================ */

import { API_BASE, AI_API_BASE } from './config.js';

/**
 * 请求失败时抛出的错误：带 HTTP 状态与后端信封，调用方可按需读取
 * @typedef {Error & {status?: number, payload?: any}} ApiError
 */

// 会话写令牌只归请求层管，页面其他部分不必知道它存在
let rankWriteToken = null;

/* 登录态失效：后端在 401 上带 X-Auth-Required 头，用来区分「没登录」和「写令牌不对」。
   两者都是 401，但处理方式完全不同——写令牌可以重领，登录态只能重新登录。
   不跳转的话页面会一直弹「未登录」，用户却不知道要去哪 */
function redirectToLogin() {
    if (location.pathname === '/login' || location.pathname === '/login.html') return;
    const target = location.pathname + location.search;
    location.replace('/login?redirect=' + encodeURIComponent(target));
}

async function requestJson(url, options) {
    const opts = Object.assign({method: 'GET'}, options || {});
    // headers 必须合并而不是覆盖，否则 Accept 会被写请求丢掉
    opts.headers = Object.assign({'Accept': 'application/json;charset=UTF-8'},
        (options || {}).headers || {});

    const res = await fetch(url, opts);

    if (res.status === 401 && res.headers.get('X-Auth-Required') === '1') {
        redirectToLogin();
        const expired = /** @type {ApiError} */ (new Error('登录已过期，正在跳转登录页'));
        expired.status = 401;
        throw expired;
    }

    let body = null;
    try {
        body = await res.json();
    } catch (e) {
        body = null; // 解析失败按 null 处理，走下面的状态码兜底
    }

    if (!res.ok || (body && typeof body.code === 'number' && body.code !== 0)) {
        const err = /** @type {ApiError} */ (new Error((body && body.message) || `HTTP ${res.status}`));
        err.status = res.status;
        err.payload = body;
        throw err;
    }
    /* 200 但 body 不是 JSON：多半是被重定向到 HTML（路由写错、SPA 兜底页）
       或上游返回了空体。此时必须抛错——返回 null 会让调用方在
       `body.data` 上抛 TypeError，错误信息彻底失真 */
    if (body === null && res.status !== 204) {
        const err = /** @type {ApiError} */ (new Error(`响应不是合法 JSON（HTTP ${res.status}）`));
        err.status = res.status;
        throw err;
    }
    return body;
}

/* 抓取接口会写库，页面先领一枚会话令牌；跨站页面读不到它，也就发不出这些 POST。 */
async function ensureWriteToken() {
    if (!rankWriteToken) {
        const body = await requestJson(`${API_BASE}/write-token`);
        rankWriteToken = body && body.data;
    }
    return rankWriteToken;
}

/* 单分类整榜抓取、榜单配置保存都是写库 POST，data.js 直接用它 */
export async function postJson(url, options) {
    const send = async () => requestJson(url, Object.assign({}, options, {
        method: 'POST',
        // headers 必须合并而不是覆盖：saveConfigApi 传的 Content-Type 会在这里丢掉，
        // 丢掉后 fetch 按 text/plain 发 JSON，后端直接 415
        headers: Object.assign({}, (options || {}).headers || {},
            {'X-Rank-Token': await ensureWriteToken()})
    }));
    try {
        return await send();
    } catch (err) {
        // 会话过期后令牌作废，重领一次再试，免得页面开着开着按钮全 401
        if (err.status !== 401 || !rankWriteToken) throw err;
        rankWriteToken = null;
        return await send();
    }
}

export function fetchRankList(url, signal) {
    return requestJson(url, {signal});
}

/* ---------- 登录态 ---------- */

/* 退出登录：服务端销毁会话。不用 postJson —— 它要写令牌，而会话可能已经过期，
   此时重领令牌只会再吃一个 401 */
export function logoutApi(signal) {
    return requestJson('/api/auth/logout', {method: 'POST', signal});
}

export function setBlockedApi(bookId, blocked, signal) {
    const path = blocked ? 'block' : 'unblock';
    return postJson(`${API_BASE}/${path}/${encodeURIComponent(bookId)}`, {signal});
}

/* 抓取深度由后端决定（displayed 的分类整个榜单），不受页面“每页显示”影响。 */
export function fetchAllDataApi(signal) {
    return postJson(`${API_BASE}/fetchAll`, {signal});
}

/* 页面唯一引导接口：榜单类型 + 分类目录一次拿全 */
export function fetchMetaApi(signal) {
    return requestJson(`${API_BASE}/meta`, {signal});
}

/* 保存榜单配置：rankType / displayCategories 两字段均可选，不传即“不修改”。
   成功回包与 meta 同形（保存后的全量视图），调用方直接用它刷新本地缓存 */
export function saveConfigApi(patch, signal) {
    const body = {};
    if (patch && patch.rankType !== undefined) {
        body.rankType = patch.rankType;
    }
    if (patch && patch.displayCategories !== undefined) {
        body.displayCategories = patch.displayCategories;
    }
    return postJson(`${API_BASE}/config`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify(body)
    });
}

/* ---------- AI 文判定 ---------- */

/* 纯读：可用引擎、各引擎待判数、判定分布 */
export function fetchAiStatusApi(signal) {
    return requestJson(`${AI_API_BASE}/status`, {signal});
}

/* 取样参数拼串：策略留空 = 用后端默认（前 10 章），此时整段不拼，
   避免把「默认」翻译成某个具体策略写死在前端 */
/* 取样规则只有三档固定配方（head / recent / random），章数在后端写死，
   所以只发 strategy，不再发 sampleCount */
function sampleQuery(sample) {
    if (!sample || !sample.strategy) return '';
    return `&sample=${encodeURIComponent(sample.strategy)}`;
}

/* 免费引擎批量初筛：limit 为本次最多判定的本数，0 表示由后端取配置默认值；
   opts = { engine, sample } */
export function sweepFreeApi(limit, opts, signal) {
    const o = opts || {};
    return postJson(`${AI_API_BASE}/sweep?limit=${encodeURIComponent(limit || 0)}`
        + `&engine=${encodeURIComponent(o.engine || '')}${sampleQuery(o.sample)}`, {signal});
}

/* 免费引擎判定单本（「校验选中」逐本调用它，便于逐行更新徽章）。
   opts = { engine, force, sample, rerun }；force=true 会忽略已有结论重判，再消耗一次模型额度；
   rerun=true 表示手点徽章触发的重跑，服务端把这一轮流水记成 rerun */
export function detectFreeApi(bookId, opts, signal) {
    const o = opts || {};
    return postJson(`${AI_API_BASE}/free/${encodeURIComponent(bookId)}`
        + `?engine=${encodeURIComponent(o.engine || '')}`
        + `&force=${o.force ? 'true' : 'false'}${sampleQuery(o.sample)}`
        + (o.rerun ? '&rerun=true' : ''), {signal});
}

/* 逐章校验推进一步：按取样条件取章节（默认前 10 章），一次判定一章。
   同一模型同一章节服务端不会重复校验；命中 AI 仍可继续，直到该模型查完这批章节 */
export function stepVerifyApi(bookId, engine, opts, signal) {
    const o = opts || {};
    return postJson(`${AI_API_BASE}/verify/${encodeURIComponent(bookId)}`
        + `?engine=${encodeURIComponent(engine || '')}${sampleQuery(o.sample)}`, {signal});
}

/* 批量取逐引擎结论：bookIds 为逗号分隔的书籍 ID（服务端上限 500） */
export function fetchVerdictsApi(bookIds, signal) {
    return requestJson(`${AI_API_BASE}/verdicts?bookIds=${encodeURIComponent((bookIds || []).join(','))}`, {signal});
}

/* 单本书的判定流水：同一引擎重判会覆盖明细，只有流水能看出「判过几次」 */
export function fetchVerdictHistoryApi(bookId, signal) {
    return requestJson(`${AI_API_BASE}/verdicts/history?bookId=${encodeURIComponent(bookId || '')}`, {signal});
}

/* ---------- 密钥配置（只回掩码，不回明文） ---------- */

export function fetchAiKeysApi(signal) {
    return requestJson(`${AI_API_BASE}/keys`, {signal});
}

/* 保存或清除单个引擎的密钥：key 传空串表示清除。写操作，需写令牌 */
export function saveAiKeyApi(engine, key, signal) {
    return postJson(`${AI_API_BASE}/keys`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify({engine, key: key || ''})
    });
}

/* 切换引擎实际调用的型号：models 数组表示型号池（随机调用），否则用单个 model */
export function saveAiModelApi(engine, model, models, signal) {
    const payload = Array.isArray(models) ? {engine, models} : {engine, model};
    return postJson(`${AI_API_BASE}/model`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify(payload)
    });
}

/* 测试一个引擎：连通性 + 输出契约。会真实发一次请求，属写操作 */
export function testAiEngineApi(id, signal) {
    return postJson(`${AI_API_BASE}/engines/test`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify({id})
    });
}

/* 新增或更新一个自定义引擎（base_url + model + key 三件套） */
export function saveAiEngineApi(payload, signal) {
    return postJson(`${AI_API_BASE}/engines`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify(payload)
    });
}

/* 删除一个自定义引擎；内置引擎只能停用 */
/* 从端点拉取型号清单：只扩充候选，不改当前型号池 */
export function discoverAiModelsApi(id, signal) {
    return postJson(`${AI_API_BASE}/engines/discover`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify({id})
    });
}

export function deleteAiEngineApi(id, signal) {
    return postJson(`${AI_API_BASE}/engines/delete`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify({id})
    });
}

/* AI 调用用量：最近 N 天的次数与 token */
export function fetchAiUsageApi(days, signal) {
    return requestJson(`${AI_API_BASE}/usage?days=${encodeURIComponent(days || 7)}`, {signal});
}

/* 各引擎额度：硅基流动查真实余额，智谱平台无接口只回规则说明。
   会触发一次外部查询，所以是 POST（与其它会出网的动作用同一套写令牌） */
export function fetchAiQuotaApi(signal) {
    return postJson(`${AI_API_BASE}/quota`, {signal});
}

/* 模型调用失败日志：最近 hours 小时的失败事实（原因 + 上游返回片段），可按引擎过滤 */
export function fetchFaultsApi(hours, engine, signal) {
    const span = encodeURIComponent(hours || 72);
    const scoped = engine ? `&engine=${encodeURIComponent(engine)}` : '';
    return requestJson(`${AI_API_BASE}/faults?hours=${span}${scoped}`, {signal});
}

/* ---------- 审查记录（只读） ---------- */

/* 一次取全三块：正在审查（内存态）/ 章节拉取 / 判定结果。
   独立页 /records 用它，参数只有窗口与每块条数 */
export function fetchRecordsApi(hours, limit, signal) {
    const span = encodeURIComponent(hours || 24);
    const size = encodeURIComponent(limit || 200);
    return requestJson(`/api/records?hours=${span}&limit=${size}`, {signal});
}

/* 解除某引擎的自动停用（写操作，与其它写动作共用写令牌） */
export function clearEngineFaultApi(engine, signal) {
    return postJson(`${AI_API_BASE}/faults/clear`, {
        signal,
        headers: {'Content-Type': 'application/json;charset=UTF-8'},
        body: JSON.stringify({engine})
    });
}
