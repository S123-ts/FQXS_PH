/* ============================================================
   js/api.js - API 请求层
   失败时优先使用后端 {code,message} 里的文案，HTTP 状态码兜底
   ============================================================ */

import { API_BASE } from './config.js';

// 会话写令牌只归请求层管，页面其他部分不必知道它存在
let rankWriteToken = null;

async function requestJson(url, options) {
    const opts = Object.assign({method: 'GET'}, options || {});
    // headers 必须合并而不是覆盖，否则 Accept 会被写请求丢掉
    opts.headers = Object.assign({'Accept': 'application/json;charset=UTF-8'},
        (options || {}).headers || {});

    const res = await fetch(url, opts);

    let body = null;
    try {
        body = await res.json();
    } catch (e) {
        body = null; // 解析失败按 null 处理，走下面的状态码兜底
    }

    if (!res.ok || (body && typeof body.code === 'number' && body.code !== 0)) {
        const err = new Error((body && body.message) || `HTTP ${res.status}`);
        err.status = res.status;
        err.payload = body;
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
