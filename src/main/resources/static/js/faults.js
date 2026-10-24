// @ts-check
/* ============================================================
   js/faults.js - 模型调用失败日志页（只服务 /faults.html）
   单独成模块：这一页没有榜单状态，不接 state.js / data.js 的生命周期，
   免得一个纯只读的排查页面去依赖整套页面 state。
   ============================================================ */

import {fetchFaultsApi, clearEngineFaultApi} from './api.js';

/** kind → 中文标签，与 AiFaultGuard.classify 的取值一一对应；新增分类要同时补这里。 */
const KIND_LABEL = /** @type {Record<string, string>} */ ({
    auth: '鉴权失败',
    rate: '限流',
    server: '上游故障',
    network: '网络/超时',
    empty: '空响应',
    parse: '解析不出概率',
    unknown: '其它'
});

/** 一次只允许一个在飞的查询：连点刷新不该把上游和自家接口都打一遍。 */
let loading = false;

function byId(id) {
    return /** @type {HTMLElement} */ (document.getElementById(id));
}

function cell(row, text, className, title) {
    const td = document.createElement('td');
    td.textContent = (text === null || text === undefined || text === '') ? '—' : String(text);
    if (className) {
        td.className = className;
    }
    if (title) {
        td.title = String(title);
    }
    row.appendChild(td);
}

function fmtTime(iso) {
    return typeof iso === 'string' && iso ? iso.replace('T', ' ').slice(0, 19) : '—';
}

function short(text, limit) {
    const value = String(text || '');
    return value.length > limit ? value.slice(0, limit) + '…' : value;
}

function renderRows(faults) {
    const tbody = byId('faultRows');
    tbody.textContent = '';
    if (!faults.length) {
        const tr = document.createElement('tr');
        const td = document.createElement('td');
        td.colSpan = 8;
        td.className = 'fault-empty';
        td.textContent = '这个窗口里没有失败记录';
        tr.appendChild(td);
        tbody.appendChild(tr);
        return;
    }
    faults.forEach(fault => {
        const tr = document.createElement('tr');
        cell(tr, fmtTime(fault.occurredTime), 'fault-time');
        cell(tr, fault.engine);
        cell(tr, fault.model);
        cell(tr, KIND_LABEL[fault.kind] || fault.kind);
        cell(tr, fault.httpStatus);
        // 原因与片段都先截断显示，完整内容挂在 title 上，避免一行撑到几十行高
        cell(tr, short(fault.reason, 160), '', fault.reason);
        cell(tr, short(fault.responseExcerpt, 220), 'fault-excerpt', fault.responseExcerpt);
        cell(tr, fault.bookId);
        tbody.appendChild(tr);
    });
}

function renderSuspended(suspended) {
    const box = byId('faultSuspended');
    box.textContent = '';
    if (!suspended.length) {
        return;
    }
    const title = document.createElement('div');
    title.textContent = `以下引擎因连续失败被自动停用（到期自动恢复，也可手动解除）：`;
    box.appendChild(title);
    suspended.forEach(item => {
        const row = document.createElement('div');
        row.className = 'fault-susp-item';

        const name = document.createElement('strong');
        name.textContent = `${item.name || item.id}（停用至 ${fmtTime(item.disableUntil)}）`;
        row.appendChild(name);

        const reason = document.createElement('span');
        reason.className = 'fault-susp-reason';
        reason.textContent = item.disableReason || '';
        row.appendChild(reason);

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-ghost';
        button.textContent = '解除停用';
        button.addEventListener('click', async () => {
            button.disabled = true;
            try {
                await clearEngineFaultApi(item.id);
                await refresh();
            } catch (err) {
                button.disabled = false;
                button.textContent = err instanceof Error ? short(err.message, 24) : '解除失败';
            }
        });
        row.appendChild(button);
        box.appendChild(row);
    });
}

/** 引擎下拉只在首次（只有一个「全部」选项时）填充，避免每次刷新都打断用户已选中的过滤。 */
function fillEngineOptions(rows) {
    const select = /** @type {HTMLSelectElement} */ (byId('faultEngine'));
    if (select.options.length > 1) {
        return;
    }
    const seen = new Set();
    rows.forEach(row => {
        if (row.engine) {
            seen.add(row.engine);
        }
        if (row.id) {
            seen.add(row.id);
        }
    });
    [...seen].sort().forEach(id => {
        const option = document.createElement('option');
        option.value = id;
        option.textContent = id;
        select.appendChild(option);
    });
}

async function refresh() {
    if (loading) {
        return;
    }
    loading = true;
    const summary = byId('faultSummary');
    const hours = /** @type {HTMLSelectElement} */ (byId('faultWindow')).value;
    const engine = /** @type {HTMLSelectElement} */ (byId('faultEngine')).value;
    summary.textContent = '正在加载…';
    try {
        const body = await fetchFaultsApi(Number(hours), engine);
        const data = (body && body.data) || {};
        const faults = Array.isArray(data.faults) ? data.faults : [];
        const suspended = Array.isArray(data.suspended) ? data.suspended : [];
        renderSuspended(suspended);
        renderRows(faults);
        fillEngineOptions([...faults, ...suspended]);
        const tally = data.kindTally || {};
        const parts = Object.entries(tally).map(([kind, count]) => `${KIND_LABEL[kind] || kind} ${count}`);
        summary.textContent = `窗口 ${data.hours || hours} 小时 · 共 ${faults.length} 条`
            + (parts.length ? ` · ${parts.join(' / ')}` : '')
            + (data.truncated ? '（已达单次返回上限，这里只是最近的部分）' : '');
    } catch (err) {
        byId('faultRows').textContent = '';
        byId('faultSuspended').textContent = '';
        summary.textContent = '加载失败：' + (err instanceof Error ? err.message : String(err));
    } finally {
        loading = false;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    byId('faultWindow').addEventListener('change', refresh);
    byId('faultEngine').addEventListener('change', refresh);
    byId('faultRefresh').addEventListener('click', refresh);
    refresh();
});
