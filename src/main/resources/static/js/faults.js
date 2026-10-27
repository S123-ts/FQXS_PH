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

/** 最近一次拉到的整批数据；类型筛选只在本地过滤，不额外打接口。 */
let latestFaults = [];
let latestTally = {};
let latestMeta = { hours: 72, truncated: false };
let faultKind = '';

function byId(id) {
    return /** @type {HTMLElement} */ (document.getElementById(id));
}

function selectOf(id) {
    return /** @type {HTMLSelectElement} */ (document.getElementById(id));
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
    return td;
}

/**
 * 原因与上游片段：完整内容进 DOM，由 CSS 裁两行，点击展开。
 * 原来用 JS 截断 + title 提示，手机上根本没有 hover，长 JSON 只能看到开头。
 */
function clipCell(row, text, className) {
    const td = document.createElement('td');
    td.className = className;
    const box = document.createElement('div');
    box.className = 'fault-clip';
    box.textContent = (text === null || text === undefined || text === '') ? '—' : String(text);
    td.appendChild(box);
    if (text) {
        td.tabIndex = 0;
        td.setAttribute('role', 'button');
        td.setAttribute('aria-expanded', 'false');
        td.title = '点击展开完整内容';
        const toggle = () => {
            const open = td.classList.toggle('is-open');
            td.setAttribute('aria-expanded', open ? 'true' : 'false');
            td.title = open ? '点击收起' : '点击展开完整内容';
        };
        td.addEventListener('click', toggle);
        td.addEventListener('keydown', e => {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                toggle();
            }
        });
    }
    row.appendChild(td);
    return td;
}

function fmtTime(iso) {
    return typeof iso === 'string' && iso ? iso.replace('T', ' ').slice(0, 19) : '—';
}

function short(text, limit) {
    const value = String(text || '');
    return value.length > limit ? value.slice(0, limit) + '…' : value;
}

function visibleFaults() {
    return faultKind ? latestFaults.filter(fault => fault.kind === faultKind) : latestFaults;
}

function emptyState(tbody, filtered) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = 8;
    td.className = 'fault-empty';

    const line = document.createElement('div');
    line.textContent = filtered ? '当前类型筛选没有记录' : '这个窗口里没有失败记录';
    td.appendChild(line);

    const hint = document.createElement('div');
    hint.className = 'fault-empty-hint';
    if (filtered) {
        hint.textContent = '点「全部」可看其余类型。';
    } else {
        hint.append('换个更长的时间窗口，或到');
        const link = document.createElement('a');
        link.href = '/records';
        link.textContent = '审查记录';
        hint.appendChild(link);
        hint.append('看看这期间的判定。');
    }
    td.appendChild(hint);

    tr.appendChild(td);
    tbody.appendChild(tr);
}

function renderRows(faults) {
    const tbody = byId('faultRows');
    tbody.textContent = '';
    if (!faults.length) {
        emptyState(tbody, !!faultKind);
        return;
    }
    faults.forEach(fault => {
        const tr = document.createElement('tr');
        cell(tr, fmtTime(fault.occurredTime), 'fault-time');
        cell(tr, fault.engine);
        cell(tr, fault.model);

        // 类型做成带色的徽章：限流（黄）与鉴权/上游故障（红）扫一眼能分开
        const typeTd = document.createElement('td');
        const kindBadge = document.createElement('span');
        kindBadge.className = 'fault-kind';
        kindBadge.dataset.kind = fault.kind || '';
        kindBadge.textContent = KIND_LABEL[fault.kind] || fault.kind || '—';
        typeTd.appendChild(kindBadge);
        tr.appendChild(typeTd);

        cell(tr, fault.httpStatus, 'fault-http');
        clipCell(tr, fault.reason, 'fault-reason');
        clipCell(tr, fault.responseExcerpt, 'fault-excerpt');
        cell(tr, fault.bookId, 'fault-book');
        tbody.appendChild(tr);
    });
}

/** 类型分布 chips：计数针对整批数据，点一下只看该类型（本地过滤）。 */
function renderKindChips() {
    const box = byId('faultKindChips');
    box.textContent = '';
    const entries = Object.entries(latestTally);
    if (!entries.length) {
        return;
    }
    entries.sort((a, b) => Number(b[1]) - Number(a[1]) || a[0].localeCompare(b[0]));

    const make = (value, label, count) => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = 'logs-chip' + (faultKind === value ? ' is-active' : '');
        chip.dataset.kind = value;
        chip.setAttribute('aria-pressed', faultKind === value ? 'true' : 'false');
        const text = document.createElement('span');
        text.textContent = label;
        chip.appendChild(text);
        const badge = document.createElement('span');
        badge.className = 'logs-chip-count';
        badge.textContent = String(count);
        chip.appendChild(badge);
        box.appendChild(chip);
    };

    make('', '全部', latestFaults.length);
    entries.forEach(([kind, count]) => make(kind, KIND_LABEL[kind] || kind, count));
}

function syncChips() {
    byId('faultKindChips').querySelectorAll('.logs-chip').forEach(el => {
        const active = (/** @type {HTMLElement} */ (el).dataset.kind || '') === faultKind;
        el.classList.toggle('is-active', active);
        el.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
}

function updateSummary() {
    const parts = Object.entries(latestTally).map(([kind, count]) => `${KIND_LABEL[kind] || kind} ${count}`);
    const head = `窗口 ${latestMeta.hours} 小时 · `
        + (faultKind ? `显示 ${visibleFaults().length} / 共 ${latestFaults.length} 条` : `共 ${latestFaults.length} 条`);
    const truncated = latestMeta.truncated ? '（已达单次返回上限，这里只是最近的部分）' : '';
    byId('faultSummary').textContent = head
        + (parts.length ? ` · ${parts.join(' / ')}` : '')
        + truncated;
}

function syncReset() {
    byId('faultReset').hidden = !(faultKind || selectOf('faultEngine').value);
}

function renderSuspended(suspended) {
    const box = byId('faultSuspended');
    box.textContent = '';
    if (!suspended.length) {
        return;
    }

    const head = document.createElement('div');
    head.className = 'fault-susp-head';
    const headTitle = document.createElement('strong');
    headTitle.textContent = '⚠ 已自动停用的引擎';
    head.appendChild(headTitle);
    const headNote = document.createElement('span');
    headNote.textContent = '连续失败达到阈值后停用，到期自动恢复；也可立即解除';
    head.appendChild(headNote);
    box.appendChild(head);

    suspended.forEach(item => {
        const row = document.createElement('div');
        row.className = 'fault-susp-item';

        const main = document.createElement('div');
        main.className = 'fault-susp-main';

        const name = document.createElement('strong');
        name.textContent = item.name || item.id;
        main.appendChild(name);

        const until = document.createElement('span');
        until.className = 'fault-susp-until';
        until.textContent = `停用至 ${fmtTime(item.disableUntil)}`;
        main.appendChild(until);

        const reason = document.createElement('span');
        reason.className = 'fault-susp-reason';
        reason.textContent = item.disableReason || '';
        main.appendChild(reason);

        row.appendChild(main);

        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'btn btn-tonal btn-sm';
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
    const select = selectOf('faultEngine');
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
    const hours = selectOf('faultWindow').value;
    const engine = selectOf('faultEngine').value;
    summary.textContent = '正在加载…';
    try {
        const body = await fetchFaultsApi(Number(hours), engine);
        const data = (body && body.data) || {};
        const faults = Array.isArray(data.faults) ? data.faults : [];
        const suspended = Array.isArray(data.suspended) ? data.suspended : [];
        latestFaults = faults;
        latestTally = data.kindTally && typeof data.kindTally === 'object' ? data.kindTally : {};
        latestMeta = { hours: Number(data.hours) || Number(hours), truncated: !!data.truncated };
        // 换引擎后原来的类型可能已经不在本批数据里，静默回落到「全部」
        if (faultKind && !Object.prototype.hasOwnProperty.call(latestTally, faultKind)) {
            faultKind = '';
        }
        renderSuspended(suspended);
        fillEngineOptions([...faults, ...suspended]);
        renderKindChips();
        renderRows(visibleFaults());
        updateSummary();
        syncReset();
    } catch (err) {
        byId('faultRows').textContent = '';
        byId('faultSuspended').textContent = '';
        byId('faultKindChips').textContent = '';
        summary.textContent = '加载失败：' + (err instanceof Error ? err.message : String(err));
    } finally {
        loading = false;
    }
}

byId('faultWindow').addEventListener('change', refresh);
byId('faultEngine').addEventListener('change', refresh);
byId('faultRefresh').addEventListener('click', refresh);
byId('faultReset').addEventListener('click', () => {
    faultKind = '';
    selectOf('faultEngine').value = '';
    refresh();
});

/* 类型 chips：本地过滤整批数据，不请求后端；计数与「全部」始终来自整批 */
byId('faultKindChips').addEventListener('click', e => {
    const target = /** @type {HTMLElement} */ (e.target);
    const chip = /** @type {HTMLElement|null} */ (target.closest('.logs-chip'));
    if (!chip) {
        return;
    }
    faultKind = chip.dataset.kind || '';
    syncChips();
    renderRows(visibleFaults());
    updateSummary();
    syncReset();
});

/* 页面脚本是 module（默认 defer），DOM 已经就绪，不必再等 DOMContentLoaded */
refresh();
