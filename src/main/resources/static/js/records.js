// @ts-check
/* ============================================================
   js/records.js - 审查记录页（只服务 /records.html）
   三块内容：正在 AI 审查（内存态）/ 正常拉取章节 / 判定结果。
   与失败日志页同一套纪律：独立模块、不接 state.js，DOM 一律 textContent 拼，
   不拼 innerHTML（书名与判定理由都来自上游，必须当不可信文本处理）。
   ============================================================ */

import { fetchRecordsApi } from './api.js';

/** 流水 kind → 中文标签，与 NovelAiVerdictLog 的常量一一对应；新增 kind 要同时补这里。 */
const KIND_LABEL = /** @type {Record<string, string>} */ ({
    sweep: '整本初筛',
    verify: '逐章校验',
    progress: '推进进度',
    rerun: '手点重跑'
});

/** aiFlag → 徽章文案与配色类，与主界面 AI 徽章同语义。 */
const FLAG = /** @type {Record<number, {label: string, cls: string}>} */ ({
    1: { label: '非 AI', cls: 'rec-flag-human' },
    2: { label: 'AI', cls: 'rec-flag-ai' },
    3: { label: '疑似', cls: 'rec-flag-suspect' }
});

const REFRESH_MS = 3000;

let loading = false;
let timer = 0;

/* 判定结果的前端检索状态：关键词由用户掌握，3 秒自动刷新只换数据，不能把它冲掉。 */
let latestResults = [];
let resultQuery = '';
let resultsLoaded = false;

function byId(id) {
    return /** @type {HTMLElement} */ (document.getElementById(id));
}

/* 表单控件单独取：byId 返回 HTMLElement，读 .value / .checked 会被 tsc 拦下 */
function selectOf(id) {
    return /** @type {HTMLSelectElement} */ (document.getElementById(id));
}

function inputOf(id) {
    return /** @type {HTMLInputElement} */ (document.getElementById(id));
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

function emptyRow(tbody, colspan, text) {
    tbody.textContent = '';
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = colspan;
    td.className = 'rec-empty';
    td.textContent = text;
    tr.appendChild(td);
    tbody.appendChild(tr);
}

/** 秒 → 「1分20秒」；不足一分钟只显示秒，省得每行都是 0分xx秒。 */
function fmtElapsed(seconds) {
    const total = Math.max(0, Number(seconds) || 0);
    if (total < 60) return `${total} 秒`;
    const min = Math.floor(total / 60);
    const rest = total % 60;
    return rest ? `${min} 分 ${rest} 秒` : `${min} 分`;
}

/** 字数按「万」压缩：章节表里一行十几万，原样打出来读不动。 */
function fmtChars(value) {
    const num = Number(value) || 0;
    if (num >= 10000) return `${(num / 10000).toFixed(1)} 万`;
    return String(num);
}

function fmtClock() {
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    return `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

/**
 * 「章节」列：这一轮判定到底用了哪几章。
 *
 * 逐章校验一行只查一章，服务端把章号写进 chapterNo（不是进度计数——
 * 取样档位是 recent/random 时进度 3 与章号 3 毫无关系）；初筛/重跑没有单章概念，
 * 用 sampleSpan（形如 `1-4,7,12`）说明取样跨度。
 */
function chapterText(item) {
    const no = Number(item.chapterNo) || 0;
    if (no > 0) return `第 ${no} 章`;
    return spanText(item.sampleSpan) || '—';
}

/** `1-4,7,12` → `第 1–4、7、12 章`；空跨度返回空串，由调用方决定显示什么 */
function spanText(span) {
    const raw = String(span || '').trim();
    if (!raw) return '';
    const parts = raw.split(',').map(part => {
        const pair = part.split('-').map(v => Number(v.trim()));
        const from = pair[0];
        if (!Number.isFinite(from)) return '';
        const to = pair[1];
        return Number.isFinite(to) && to !== from ? `${from}–${to}` : `${from}`;
    }).filter(Boolean);
    return parts.length ? `第 ${parts.join('、')} 章` : '';
}

/* ---------- 三块渲染 ---------- */

function renderRunning(running) {
    const box = byId('recRunning');
    byId('recRunningCount').textContent = String(running.length);
    box.textContent = '';
    if (!running.length) {
        const p = document.createElement('p');
        p.className = 'rec-empty';
        p.textContent = '当前没有进行中的审查';
        box.appendChild(p);
        return;
    }
    running.forEach(item => {
        const row = document.createElement('div');
        row.className = 'rec-run-item';

        const dot = document.createElement('span');
        dot.className = 'rec-dot';
        dot.setAttribute('aria-hidden', 'true');
        row.appendChild(dot);

        const kind = document.createElement('span');
        kind.className = 'rec-run-kind';
        kind.textContent = item.kindLabel || KIND_LABEL[item.kind] || item.kind;
        row.appendChild(kind);

        if (item.title) {
            const book = document.createElement('span');
            book.className = 'rec-run-book';
            book.textContent = item.title;
            book.title = item.bookId || '';
            row.appendChild(book);
        }

        if (item.detail) {
            const detail = document.createElement('span');
            detail.textContent = `· ${item.detail}`;
            row.appendChild(detail);
        }

        const meta = document.createElement('span');
        meta.className = 'rec-run-meta';
        meta.textContent = `${item.engine || '—'} · 已用 ${fmtElapsed(item.elapsedSeconds)}`;
        row.appendChild(meta);

        box.appendChild(row);
    });
}

function renderChapters(chapters) {
    const tbody = byId('recFetchRows');
    byId('recFetchCount').textContent = String(chapters.length);
    if (!chapters.length) {
        emptyRow(tbody, 6, '这个窗口里没有拉取记录（可能都命中了缓存，没重新抓）');
        return;
    }
    tbody.textContent = '';
    chapters.forEach(item => {
        const tr = document.createElement('tr');
        // 书名格带上 bookId：书名可能重复，排查时靠 ID 定位
        const titleTd = document.createElement('td');
        titleTd.className = 'rec-book';
        const titleText = document.createElement('span');
        titleText.textContent = item.title || item.bookId || '—';
        titleTd.appendChild(titleText);
        const idText = document.createElement('span');
        idText.className = 'rec-book-id';
        idText.textContent = item.bookId || '';
        titleTd.appendChild(idText);
        tr.appendChild(titleTd);

        cell(tr, item.chapters, 'num');
        cell(tr, fmtChars(item.chars), 'num');
        // chapter_index 是 0-based，页面说「第几章」要 +1
        cell(tr, (Number(item.maxIndex) || 0) + 1, 'num');
        cell(tr, item.lastFetched, 'rec-time');
        // 残留 PUA 正常恒为 0，非 0 说明映射表落后于上游，值得眼睛停一下
        const pua = Number(item.puaUnresolved) || 0;
        cell(tr, pua > 0 ? pua : '—', pua > 0 ? 'num rec-reason' : 'num');
        tbody.appendChild(tr);
    });
}

function renderResults(results) {
    latestResults = Array.isArray(results) ? results : [];
    resultsLoaded = true;
    const tbody = byId('recResultRows');
    const query = resultQuery.trim().toLowerCase();
    const visible = query
        ? latestResults.filter(item => String(item.title || '').toLowerCase().includes(query))
        : latestResults;
    byId('recResultCount').textContent = query
        ? `${visible.length} / ${latestResults.length}`
        : String(latestResults.length);
    if (!latestResults.length) {
        emptyRow(tbody, 8, '这个窗口里没有判定结果');
        return;
    }
    if (!visible.length) {
        emptyRow(tbody, 8, `没有书名包含「${resultQuery.trim()}」的判定结果`);
        return;
    }
    tbody.textContent = '';
    visible.forEach(item => {
        const tr = document.createElement('tr');
        cell(tr, item.checkedTime, 'rec-time');

        const titleTd = document.createElement('td');
        titleTd.className = 'rec-book';
        const titleText = document.createElement('span');
        titleText.textContent = item.title || item.bookId || '—';
        titleTd.appendChild(titleText);
        tr.appendChild(titleTd);

        // 型号行会带 #verify 后缀（逐章校验写的是独立明细行），「类型」列已经说明动作，显示时去掉
        const model = String(item.model || '').replace(/#verify$/, '');
        cell(tr, `${item.engine || '—'} · ${model || '—'}`);

        const flag = FLAG[Number(item.aiFlag)];
        const flagTd = document.createElement('td');
        const badge = document.createElement('span');
        badge.className = `rec-flag ${flag ? flag.cls : 'rec-flag-progress'}`;
        // aiFlag=0 是逐章校验的中间步：这一章没命中，本轮不产生结论
        badge.textContent = flag ? flag.label : '未命中';
        flagTd.appendChild(badge);
        tr.appendChild(flagTd);

        cell(tr, item.aiScore === null || item.aiScore === undefined ? '—' : `${item.aiScore}%`, 'num');
        /* 「章节」列：逐章校验显示这一轮查的那一章，初筛/重跑显示取样跨度。
           跨度可能是「第 990–994、995–999 章」这种长串，超出列宽要省略号而不是溢出——
           和理由列同一个理由：overflow/text-overflow 挂在 table-cell 上不生效，必须套一层 div */
        const chapterLabel = chapterText(item);
        const chapterTd = document.createElement('td');
        chapterTd.className = 'num';
        const chapterDiv = document.createElement('div');
        chapterDiv.className = 'rec-chapter';
        chapterDiv.textContent = chapterLabel;
        chapterDiv.title = chapterLabel;
        chapterTd.appendChild(chapterDiv);
        tr.appendChild(chapterTd);
        cell(tr, KIND_LABEL[item.kind] || item.kind);

        /* 理由要限高，但 max-height 对 table-cell 无效——必须套一层 div 才裁得掉 */
        const reasonText = String(item.reason || '').trim();
        const reasonTd = document.createElement('td');
        const reasonDiv = document.createElement('div');
        reasonDiv.className = 'rec-reason';
        reasonDiv.textContent = reasonText || '—';
        reasonDiv.title = reasonText;
        reasonTd.appendChild(reasonDiv);
        tr.appendChild(reasonTd);
        tbody.appendChild(tr);
    });
}

function renderKpi(payload) {
    const chapters = payload.chapters || [];
    const chapterCount = chapters.reduce((sum, item) => sum + (Number(item.chapters) || 0), 0);
    byId('kpiFetch').textContent = String(chapters.length);
    byId('kpiFetchSub').textContent = `本书 / ${chapterCount} 章`;

    byId('kpiRunning').textContent = String((payload.running || []).length);

    const tally = payload.tally || {};
    const ai = Number(tally['2']) || 0;
    const suspect = Number(tally['3']) || 0;
    const human = Number(tally['1']) || 0;
    byId('kpiResults').textContent = String(ai + suspect + human);
    byId('kpiResultsSub').textContent = `AI ${ai} / 疑似 ${suspect} / 非 AI ${human}`;
}

/* ---------- 拉数据 ---------- */

/* 数据签名：3 秒刷新时只有真正变化的块才重绘。
   全量重建会把用户正在选中的文本清掉，滚动位置也可能跳 */
let lastChaptersSig = '';
let lastResultsSig = '';

async function refresh() {
    if (loading) return;
    loading = true;
    const hours = Number(selectOf('recWindow').value) || 24;
    try {
        const body = await fetchRecordsApi(hours, 200);
        const payload = (body && body.data) || {};
        renderKpi(payload);
        // 正在审查的「已运行 N 秒」每轮都在变，这块保持每轮重绘
        renderRunning(payload.running || []);
        const chaptersSig = JSON.stringify(payload.chapters || []);
        if (chaptersSig !== lastChaptersSig) {
            renderChapters(payload.chapters || []);
            lastChaptersSig = chaptersSig;
        }
        const resultsSig = JSON.stringify(payload.results || []);
        if (resultsSig !== lastResultsSig) {
            renderResults(payload.results || []);
            lastResultsSig = resultsSig;
        }
        byId('recUpdated').textContent = `更新于 ${fmtClock()} · 窗口 ${hours} 小时`;
    } catch (err) {
        // 401 由 api.js 统一跳登录页；这里只报其他失败，避免把「正在跳转」记为错误
        if (err && err.status === 401) return;
        byId('recUpdated').textContent = `刷新失败：${(err && err.message) || '未知错误'}`;
    } finally {
        loading = false;
    }
}

/** 自动刷新：页面在后台时跳过（省接口与 CPU），回到前台立刻补一次。 */
function tick() {
    if (document.hidden) return;
    refresh();
}

function startAuto() {
    stopAuto();
    if (inputOf('recAuto').checked) {
        timer = window.setInterval(tick, REFRESH_MS);
    }
}

function stopAuto() {
    if (timer) {
        clearInterval(timer);
        timer = 0;
    }
}

document.getElementById('recWindow').addEventListener('change', refresh);
document.getElementById('recRefresh').addEventListener('click', refresh);
document.getElementById('recAuto').addEventListener('change', () => {
    startAuto();
    if (inputOf('recAuto').checked) refresh();
});
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && inputOf('recAuto').checked) refresh();
});

const resultSearch = inputOf('resultSearch');
resultSearch.addEventListener('input', () => {
    resultQuery = resultSearch.value;
    if (resultsLoaded) {
        renderResults(latestResults);
    }
});
resultSearch.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        resultSearch.value = '';
        resultQuery = '';
        if (resultsLoaded) {
            renderResults(latestResults);
        }
    }
});

refresh();
startAuto();
