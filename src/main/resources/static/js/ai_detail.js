// @ts-check
/* ============================================================
   js/ai_detail.js - 「AI 判定详情」面板
   卡片头按「引擎 × 型号」各取一条（pickModelVerdicts，与行内徽章同一优先级：
   逐章命中优先，否则最新）；卡内的「校验记录」只列该型号的每一轮判定——
   卡内的「校验记录」打开面板后按 engine 异步拉 /api/ai/verdicts/history——
   明细表同型号重判会覆盖，只有流水能看出「这本书被谁判过几次、每次什么结论」。
   ============================================================ */

import { state } from './state.js';
import { fetchVerdictHistoryApi } from './api.js';
import { escapeText, escapeAttr, aiEngineName, formatAiScore, pickModelVerdicts, verdictBaseModel, verdictModelShort } from './render.js';

// 打开面板的触发按钮，关闭时把焦点还给它
let detailOpener = null;

/* 当前面板展示的书：异步拉历史返回时用它守卫，避免快速切书把旧结果填进新面板 */
let detailBookId = null;

/* 每张卡最多列多少轮：逐章校验按「型号 × 章节」累积（3 型号 × 10 章 = 30 轮），
   上限跟着放大，否则用户只能看到最近几次，等于没写进详情 */
const HISTORY_ROWS_SHOWN = 30;

export function isAiDetailOpen() {
    const mask = document.getElementById('aiDetailMask');
    return !!mask && !mask.hidden;
}

function flagMeta(flag) {
    if (flag === 2) return { label: 'AI', cls: 'ai' };
    if (flag === 3) return { label: '疑似 AI', cls: 'suspect' };
    if (flag === 1) return { label: '非 AI', cls: 'human' };
    return { label: '未完成', cls: 'human' };
}

/* 取样档位翻成人话：free/member + 策略 + 实际章号 */
function sampleLabel(row) {
    const mode = String(row.sampleMode || '');
    if (!mode) return '';
    const [channel, strategy] = mode.split(':');
    const strategyName = {
        mid_recent: '中段+最新', head: '前N章', mid: '中段', recent: '最新', random: '随机'
    }[strategy] || strategy || '';
    const channelName = channel === 'member' ? '会员全文' : '免费区';
    const span = row.sampleSpan ? `第${row.sampleSpan}章` : '';
    return [channelName, strategyName, span].filter(Boolean).join(' · ');
}

function renderCard(row) {
    const pending = row.placeholder === true;
    const flag = pending ? { label: '未判定', cls: 'human' } : flagMeta(Number(row.aiFlag));
    const engine = aiEngineName(row.engine);
    // 卡片按型号分开：头部带型号短名，完整型号在 title 与 meta 里可查
    const model = pending ? '' : verdictModelShort(row.model);
    const checked = Number(row.chaptersChecked || 0);
    // 初筛是整本判定，chaptersChecked=0；逐章校验才按章计进度
    const scope = checked > 0 ? `逐章校验 ${checked} 章` : '整本初筛';
    const meta = pending ? '已配置密钥，但还没有判定这本书' : `${scope} · ${row.checkedTime || '—'}`;
    const tech = [
        row.promptId ? `提示词 ${row.promptId}` : '',
        sampleLabel(row)
    ].filter(Boolean).join(' · ');
    const reason = pending
        ? '该引擎尚未对这本书作出判定'
        : (row.reason && row.reason.trim() ? row.reason.trim() : '该引擎未返回理由');
    // 校验记录占位：打开面板后异步填充，先给一行「加载中」保持高度稳定
    const history = pending
        ? ''
        : `<div class="ai-log-host" data-engine="${escapeAttr(row.engine || '')}"
                data-model="${escapeAttr(verdictBaseModel(row.model))}">
               <div class="ai-log-status">校验记录加载中…</div>
           </div>`;
    return `
        <div class="ai-detail-card${pending ? ' pending' : ''}">
            <div class="ai-detail-head">
                <span class="ai-detail-engine"
                      title="${escapeAttr(row.engine + (pending ? '' : ' / ' + modelLabel(row.model)))}">${escapeText(engine + (model ? ' · ' + model : ''))}</span>
                <span class="ai-detail-score">${pending ? '—' : escapeText(formatAiScore(row.aiScore))}</span>
                <span class="ai-badge ${flag.cls}">${flag.label}</span>
            </div>
            <div class="ai-detail-meta">${escapeText(meta)}</div>
            ${tech ? `<div class="ai-detail-meta">${escapeText(tech)}</div>` : ''}
            <p class="ai-detail-reason font-DNMrHsV173Pd4pgy">${escapeText(reason)}</p>
            ${history}
        </div>
    `;
}

/* 详情卡按「引擎 × 型号」展开：判过的型号每个一张卡；某引擎一张卡都没有时补一张「未判定」。
   只列判过的型号会把「还没跑过 QW」藏起来，看起来像系统只有 GLM 一个引擎。 */
function mergeConfiguredModels(rows) {
    const byModel = new Map(pickModelVerdicts(rows).map(row => [
        `${row.engine || ''}|${verdictBaseModel(row.model)}`, row]));
    const configured = (state.aiStatus && state.aiStatus.verifyEngines) || [];
    configured.forEach(engine => {
        const id = engine && engine.id;
        if (id && !rows.some(row => row.engine === id)) {
            byModel.set(`${id}|`, { engine: id, placeholder: true });
        }
    });
    return [...byModel.values()];
}

/* 一轮流水在做什么：初筛是整本，rerun 是手点模型名重跑，verify/progress 是逐章的第几步 */
function roundScope(entry) {
    const checked = Number(entry.chaptersChecked || 0);
    if (entry.kind === 'rerun') return '手点重跑';
    if (entry.kind === 'sweep') return '整本初筛';
    if (entry.kind === 'progress') return `第 ${checked} 章·试读跳过`;
    return checked > 0 ? `逐章第 ${checked} 章` : '逐章校验';
}

/* 逐章行的型号带 #verify 后缀（与整本初筛分行存储），展示时去掉 */
function modelLabel(model) {
    return String(model || '').replace(/#verify$/, '');
}

/* 「10-22 01:02」：同一天的多轮靠时分秒区分，年份对追溯没有价值 */
function shortTime(value) {
    const text = String(value || '');
    return text.length >= 16 ? text.slice(5, 16) : text;
}

function renderHistory(entries) {
    if (!entries || entries.length === 0) {
        return '<div class="ai-log-status">暂无校验记录（该结论产生于流水功能上线前）</div>';
    }
    const shown = entries.slice(0, HISTORY_ROWS_SHOWN);
    const rows = shown.map(entry => {
        const flag = flagMeta(Number(entry.aiFlag));
        const tech = [modelLabel(entry.model), entry.promptId ? `提示词 ${entry.promptId}` : '']
            .filter(Boolean).join(' · ');
        return `
            <li class="ai-log-row">
                <span class="ai-log-time">${escapeText(shortTime(entry.checkedTime))}</span>
                <span class="ai-badge ${flag.cls}">${flag.label}</span>
                <span class="ai-log-score">${escapeText(formatAiScore(entry.aiScore))}</span>
                <span class="ai-log-scope">${escapeText(roundScope(entry))}</span>
                <span class="ai-log-tech" title="${escapeAttr(tech)}">${escapeText(tech)}</span>
            </li>`;
    }).join('');
    const more = entries.length > shown.length
        ? `<div class="ai-log-status">仅显示最近 ${shown.length} 轮，另有 ${entries.length - shown.length} 轮更早的记录未列出</div>`
        : '';
    return `
        <details class="ai-log" open>
            <summary class="ai-log-summary">校验记录 ${entries.length} 轮</summary>
            <ul class="ai-log-list">${rows}</ul>
            ${more}
        </details>`;
}

/* 按引擎把流水塞回各自卡片的占位里。
   只改占位节点、不重渲染整个列表：整块 innerHTML 会重置滚动位置，
   用户刚翻到第 4 张卡时列表跳回顶部。 */
function fillHistory(bookId, entries) {
    const body = document.getElementById('aiDetailBody');
    if (!body || detailBookId !== bookId) return;
    const byModel = new Map();
    const keyOf = (row) => `${row.engine || ''}|${verdictBaseModel(row.model)}`;
    for (const entry of entries) {
        const key = keyOf(entry);
        if (!byModel.has(key)) byModel.set(key, []);
        byModel.get(key).push(entry);
    }
    body.querySelectorAll('.ai-log-host').forEach(host => {
        const key = `${host.getAttribute('data-engine') || ''}|${host.getAttribute('data-model') || ''}`;
        host.innerHTML = renderHistory(byModel.get(key) || []);
    });
}

export function openAiDetail(bookId, opener) {
    const mask = document.getElementById('aiDetailMask');
    const body = document.getElementById('aiDetailBody');
    const title = document.getElementById('aiDetailTitle');
    const hint = document.getElementById('aiDetailHint');
    if (!mask || !body || !bookId) return;

    const books = state.lastData && Array.isArray(state.lastData.data) ? state.lastData.data : [];
    const book = books.find(item => String(item.bookId) === String(bookId));
    const rows = mergeConfiguredModels(((state.aiVerdicts || {})[bookId] || []).slice());
    // 固定引擎顺序、型号按字典序，避免数据库返回顺序变化导致每次打开顺序不同
    const order = ['glm-4-flash', 'qianfan-ernie-speed', 'qwen2.5-7b'];
    rows.sort((a, b) => {
        const ai = order.indexOf(a.engine);
        const bi = order.indexOf(b.engine);
        const byEngine = (ai === -1 ? order.length : ai) - (bi === -1 ? order.length : bi);
        return byEngine !== 0 ? byEngine : verdictBaseModel(a.model).localeCompare(verdictBaseModel(b.model));
    });

    if (title) {
        title.textContent = book && book.title ? `AI 判定详情 · ${book.title}` : 'AI 判定详情';
    }
    if (hint) {
        hint.textContent = '卡片按型号展示最新一次结论；卡内「校验记录」列出该型号的每一轮判定（含被覆盖的旧结论）';
    }
    body.innerHTML = rows.length
        ? `<div class="ai-detail-list">${rows.map(renderCard).join('')}</div>`
        : '<p class="ai-detail-empty">暂无结论。先配置密钥，再勾选书籍点「校验选中」或点「AI 初筛」。</p>';

    mask.hidden = false;
    detailOpener = opener instanceof Element ? opener : null;
    const close = /** @type {HTMLElement} */ (mask.querySelector('.modal-close'));
    if (close) close.focus();

    detailBookId = String(bookId);
    loadHistory(String(bookId));
}

/* 流水按需拉：只查这一本书，失败就把占位改成一行提示，不打断面板 */
function loadHistory(bookId) {
    fetchVerdictHistoryApi(bookId)
        .then(body => {
            const entries = body && Array.isArray(body.data) ? body.data : [];
            fillHistory(bookId, entries);
        })
        .catch(err => {
            console.error('判定流水加载失败:', err);
            if (detailBookId !== bookId) return;
            document.querySelectorAll('#aiDetailBody .ai-log-host').forEach(host => {
                host.innerHTML = `<div class="ai-log-status">校验记录加载失败：${escapeText(err.message)}</div>`;
            });
        });
}

/* 重跑完成后刷新当前打开的详情面板：流水是多轮记录，刚产生的那一条要立刻出现。
   只刷新同一本书；面板没开或开的是别的书时什么都不做 */
export function refreshOpenAiDetail(bookId) {
    if (!detailBookId || String(bookId) !== detailBookId) return;
    loadHistory(detailBookId);
}

export function closeAiDetail() {
    const mask = document.getElementById('aiDetailMask');
    if (!mask || mask.hidden) return;
    mask.hidden = true;
    detailBookId = null;
    if (detailOpener && document.contains(detailOpener)) {
        detailOpener.focus();
    }
    detailOpener = null;
}

/* Tab 焦点圈定：焦点在对话框内循环，不漏到遮罩底下的页面 */
export function trapFocusInAiDetail(e) {
    const mask = document.getElementById('aiDetailMask');
    const dialog = mask && mask.querySelector('.modal');
    if (!dialog) return;
    const focusables = /** @type {HTMLElement[]} */ (
        Array.from(dialog.querySelectorAll('button, a[href], input')))
        .filter(el => !/** @type {HTMLButtonElement} */ (el).disabled);
    if (focusables.length === 0) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    const outside = !dialog.contains(active);
    if (e.shiftKey) {
        if (active === first || outside) {
            e.preventDefault();
            last.focus();
        }
    } else if (active === last || outside) {
        e.preventDefault();
        first.focus();
    }
}
