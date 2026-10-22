// @ts-check
/* ============================================================
   js/render.js - DOM 渲染与 toast
   渲染纪律：不写内联 style、不写内联事件、不塞 <style>；
   显隐一律用 hidden 属性，下拉开合只用 #dropdownMenu 的 show 类
   ============================================================ */

import { state, getEffectivePageSize } from './state.js';
import {
    getDisplayedGroups, getDisplayedCategories, getRankTypes,
    getActiveRankType, findRankType, categoryDisplayName
} from './meta.js';

export function escapeText(value) {
    if (value === null || value === undefined) return '';
    const div = document.createElement('div');
    div.textContent = String(value);
    return div.innerHTML;
}

/* 放进 HTML 属性前必须连引号一起转义，textContent 那套不会处理引号 */
export function escapeAttr(value) {
    return escapeText(value).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/* ---------- 静态骨架的动态内容 ---------- */

/* 导航：全部在最前，之后按 男频 / 女频 两组铺「筛选下拉勾选集」里的分类。
   勾选集即导航显示集：下拉里取消勾选的分类按钮同步隐藏，勾回即恢复。
   男女频存在同名分类（如“科幻末世”），分组标签让归属一眼可辨 */
export function renderNav() {
    const nav = document.getElementById('navInner');
    if (!nav) return;
    const shown = new Set(state.selectedCategories);
    const btn = (code, name) =>
        `<button class="nav-btn ${code === state.currentCategory ? 'active' : ''}"
                 data-code="${escapeAttr(code)}">${escapeText(name)}</button>`;
    const parts = [btn('ALL', '全部')];
    getDisplayedGroups().forEach(group => {
        const items = group.items.filter(cat => shown.has(cat.code));
        if (items.length === 0) return;
        parts.push(`<span class="nav-group-label">${escapeText(group.label)}</span>`);
        items.forEach(cat => parts.push(btn(cat.code, cat.name)));
    });
    nav.innerHTML = parts.join('');
}

/* 榜单类型分段单选：真实 radio 保证键盘/读屏可用，视觉样式落在后面的 pill 上 */
export function renderRankTypeControls() {
    const host = document.getElementById('rankTypeGroup');
    if (!host) return;
    const active = getActiveRankType();
    host.innerHTML = getRankTypes().map(rt => `
        <label class="rank-type-option">
            <input type="radio" name="rankType" value="${escapeAttr(rt.code)}"${rt.code === active ? ' checked' : ''}>
            <span class="rank-type-pill"><span class="rank-type-dot" aria-hidden="true"></span>${escapeText(rt.name)}</span>
        </label>
    `).join('');
}

/* 切换请求在飞行中时锁住整组，防止连点发出并发保存 */
export function setRankTypeControlsDisabled(disabled) {
    const host = document.getElementById('rankTypeGroup');
    if (!host) return;
    host.querySelectorAll('input[type="radio"]').forEach(input => {
        /** @type {HTMLInputElement} */ (input).disabled = disabled;
    });
}

/* 统计栏的榜单名随 meta 走；meta 未就绪时保持 HTML 里的占位符 */
export function updateStatsMeta() {
    const el = document.getElementById('currentRankType');
    if (!el) return;
    const rt = findRankType(getActiveRankType());
    el.textContent = rt ? rt.name : '—';
    updateChromeSummary();
}

/* 重建分类多选下拉；bootstrap 与保存展示分类后都会调这里。
   首次默认全选 displayed；之后尊重用户勾选，只裁掉不再展示的 code，
   不能强行恢复全选——那会把用户刻意缩小/清空的选择悄悄改回去 */
export function populateFilterCategories() {
    const menu = document.getElementById('dropdownMenu');
    if (!menu) return;
    menu.innerHTML = '';

    const displayed = getDisplayedCategories();
    if (!state.filterReady) {
        state.selectedCategories = displayed.map(c => c.code);
        state.filterReady = true;
    } else {
        const shown = new Set(displayed.map(c => c.code));
        state.selectedCategories = state.selectedCategories.filter(code => shown.has(code));
    }

    const allItem = document.createElement('div');
    allItem.className = 'dropdown-item check-all';
    // #checkAll 是契约钩子；checkbox 的变化由 events.js 委托到 #dropdownMenu 上
    allItem.innerHTML = `
        <input type="checkbox" id="checkAll" checked>
        <label for="checkAll">全选</label>
    `;
    menu.appendChild(allItem);

    const divider = document.createElement('hr');
    divider.className = 'dropdown-divider'; // 样式交给 CSS，不再写内联 border
    menu.appendChild(divider);

    displayed.forEach(cat => {
        const item = document.createElement('div');
        item.className = 'dropdown-item';
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = state.selectedCategories.includes(cat.code);
        input.dataset.cat = cat.code; // allBooks 收 code，不再传名字
        const label = document.createElement('label');
        label.append(input, cat.name); // label 包住 input，点文字同样能勾选
        item.appendChild(label);
        menu.appendChild(item);
    });

    updateDropdownLabel();
    updateCheckAllState();
}

export function updateCheckAllState() {
    const allCheckbox = /** @type {HTMLInputElement} */ (document.getElementById('checkAll'));
    if (!allCheckbox) return;
    const total = getDisplayedCategories().length;
    if (total === 0) {
        // 展示分类为空时 0===0 恒真会把「全选」画成勾选，与“未选择”标签自相矛盾
        allCheckbox.checked = false;
        allCheckbox.indeterminate = false;
        return;
    }
    allCheckbox.checked = (state.selectedCategories.length === total);
    allCheckbox.indeterminate = (state.selectedCategories.length > 0 && state.selectedCategories.length < total);
}

export function updateDropdownLabel() {
    const label = document.getElementById('dropdownLabel');
    if (!label) return;
    const total = getDisplayedCategories().length;
    if (state.selectedCategories.length === 0) {
        label.textContent = '未选择';
    } else if (state.selectedCategories.length === total) {
        label.textContent = '全部';
    } else {
        label.textContent = '已选 ' + state.selectedCategories.length + ' 个';
    }
}

/* ---------- 手机折叠摘要条 ----------
   小屏默认把「统计 + 控制 + 筛选」收成一行：首屏不再有一半是控件。
   展开态只存 sessionStorage——下次打开回到折叠，避免用户忘了收起。 */

const CHROME_OPEN_KEY = 'fqxs.chromeOpen';
const chromeMobile = window.matchMedia('(max-width: 768px)');

function readChromeOpen() {
    try {
        return sessionStorage.getItem(CHROME_OPEN_KEY) === '1';
    } catch {
        return false; // 隐私模式等场景 sessionStorage 可能直接抛错
    }
}

let chromeOpen = readChromeOpen();

function selectedLabel(id, fallback) {
    const el = /** @type {HTMLSelectElement} */ (document.getElementById(id));
    const option = el && el.selectedOptions && el.selectedOptions[0];
    return option ? option.textContent.trim() : fallback;
}

/* 摘要必须把「当前在筛什么」写出来：折叠后控件不可见，
   只剩一条摘要，筛了却看不见等于把状态藏起来 */
export function updateChromeSummary() {
    const text = document.getElementById('chromeSummaryText');
    if (!text) return;
    const rankEl = document.getElementById('currentRankType');
    const rank = rankEl ? rankEl.textContent : '—';
    const filterBar = document.getElementById('filterBar');
    if (filterBar && filterBar.hidden) {
        const catEl = document.getElementById('currentCategory');
        text.textContent = `榜单 ${rank} · 分类 ${catEl ? catEl.textContent : '全部'}`;
        return;
    }
    const parts = [
        `榜单 ${rank}`,
        `字数 ${selectedLabel('filterWord', '全部')}`,
        `状态 ${selectedLabel('filterStatus', '全部')}`
    ];
    const blocked = /** @type {HTMLInputElement} */ (document.getElementById('filterBlocked'));
    if (blocked && blocked.checked) parts.push('只看已拉黑');
    text.textContent = parts.join(' · ');
}

function applyChromeState() {
    const bar = document.getElementById('statsBar');
    const summary = document.getElementById('chromeSummary');
    const action = document.getElementById('chromeSummaryActionText');
    if (!bar || !summary) return;
    // 桌面始终展开；小屏才看会话内的展开态。类本身只在小屏媒体查询里生效
    const open = chromeMobile.matches ? chromeOpen : true;
    bar.classList.toggle('chrome-collapsed', !open);
    summary.setAttribute('aria-expanded', String(open));
    if (action) action.textContent = open ? '收起' : '筛选';
}

export function toggleChromeCollapse() {
    chromeOpen = !chromeOpen;
    try {
        sessionStorage.setItem(CHROME_OPEN_KEY, chromeOpen ? '1' : '0');
    } catch {
        // 存不进去就只影响下次访问，不打断本次操作
    }
    applyChromeState();
}

/* 摘要条吸顶的偏移量 = 导航实际高度（字号/系统缩放变化后要重算） */
function syncNavHeight() {
    const nav = document.getElementById('categoryNav');
    if (!nav) return;
    document.documentElement.style.setProperty('--nav-h', nav.getBoundingClientRect().height + 'px');
}

/* 桌面吸顶工具条：哨兵越过导航底边即收起为工具条。
   用哨兵而不是卡片自身位置——卡片吸住后 getBoundingClientRect 不再反映文档位置 */
function initPinnedToolbar() {
    const bar = document.getElementById('statsBar');
    const nav = document.getElementById('categoryNav');
    if (!bar || !nav || !bar.parentNode) return;
    const sentinel = document.createElement('div');
    sentinel.className = 'stats-pin-sentinel';
    sentinel.setAttribute('aria-hidden', 'true');
    bar.parentNode.insertBefore(sentinel, bar);
    const desktop = window.matchMedia('(min-width: 1100px)');
    let ticking = false;
    const update = () => {
        ticking = false;
        if (!desktop.matches) {
            bar.classList.remove('is-pinned');
            return;
        }
        bar.classList.toggle('is-pinned',
            sentinel.getBoundingClientRect().top < nav.getBoundingClientRect().height);
    };
    const requestUpdate = () => {
        if (ticking) return;
        ticking = true;
        requestAnimationFrame(update);
    };
    window.addEventListener('scroll', requestUpdate, { passive: true });
    window.addEventListener('resize', requestUpdate);
    desktop.addEventListener('change', requestUpdate);
    update();
}

export function initChromeUi() {
    applyChromeState();
    updateChromeSummary();
    syncNavHeight();
    initPinnedToolbar();
    window.addEventListener('resize', syncNavHeight);
    chromeMobile.addEventListener('change', applyChromeState);
    // 导航按钮由 JS 后填、字号也会随系统缩放变化：高度一变就重算吸顶偏移，
    // 只靠初始化时量一次会拿到空导航的 17px
    const nav = document.getElementById('categoryNav');
    if (nav && window.ResizeObserver) {
        new ResizeObserver(syncNavHeight).observe(nav);
    }
}

/* ---------- #bookList 的各形态 ---------- */

export function renderLoading() {
    document.getElementById('bookList').innerHTML = `
        <div class="loading">
            <div class="spinner"></div>
            <p>加载中...</p>
        </div>
    `;
}

function renderEmpty(icon, title, detail) {
    return `
        <div class="empty">
            <span class="icon">${icon}</span>
            <p>${escapeText(title)}</p>
            ${detail ? `<p class="hint">${escapeText(detail)}</p>` : ''}
        </div>
    `;
}

/* 空态/失败态整体替换列表内容 */
export function renderEmptyState(icon, title, detail) {
    document.getElementById('bookList').innerHTML = renderEmpty(icon, title, detail);
}

/* 封面直链是带签名的 CDN 地址，过期后 403；加载失败由 events.js 捕获阶段的
   error 监听移除 <img>，这里绝不能再写内联 onerror。 */
function renderCover(book) {
    const img = book.thumbUri
        ? `<img src="${escapeAttr(book.thumbUri)}" alt="" loading="lazy" decoding="async">`
        : '';
    return `
        <div class="cover">
            <span class="cover-ph">📖</span>
            ${img}
        </div>
    `;
}

function renderChips(book) {
    return [
        /* 数值与单位之间不留空格：中文语境下空格会把“196.4万”和“人在读”拉成两段，
           看上去像两条独立信息。字数同理，补上单位与阅读数保持同一句式。 */
        ['📖', book.readCount ? book.readCount + '人在读' : ''],
        ['📝', book.wordCount ? book.wordCount + '字' : ''],
        ['🏷️', book.categoryName || ''],
        ['🕐', book.updateTime ? '更新于 ' + book.updateTime : '']
    ].filter(item => item[1])
        .map(item => `<span class="chip"><i>${item[0]}</i>${escapeText(item[1])}</span>`)
        .join('');
}

/* 引擎展示名与 AI 率格式：徽章与详情弹窗共用同一套，
   避免同一结论在两处显示成不同精度 */
const ENGINE_DISPLAY = {
    'glm-4-flash': 'GLM',
    'qianfan-ernie-speed': 'ERNIE',
    'qwen2.5-7b': 'QW'
};

export function aiEngineName(engine) {
    if (ENGINE_DISPLAY[engine]) return ENGINE_DISPLAY[engine];
    // 用户自建引擎没有短名映射：去掉 custom- 前缀并截前 6 位，避免整串 id 挤进徽章
    return String(engine || '').replace(/^custom-/, '').slice(0, 6);
}

export function formatAiScore(score) {
    if (score === null || score === undefined || score === '') return '—';
    const value = Number(score);
    if (!Number.isFinite(value)) return '—';
    return `${Math.round(value * 10) / 10}%`;
}

/* 各引擎徽章：每个 API 的结论与 AI 率单独展示（GLM / ERNIE / QW），互不合并——
   引擎之间结论不同时，用户一眼能看出分歧。无结论的引擎不显示。

   **徽章只放结论**。逐章校验的进度行（aiFlag=0）是"查了 n 章但还没出结论"的游标，
   不是结论，一律不参与挑选——它由逐章校验按钮承载（`ERNIE 4/20`）。两处各显示一份
   必然对不上：2026-10-22 就出现过徽章「GLM·查验中 1」与按钮「GLM·AI 3/40」同时在场，
   而且那两条进度行早已冻结（没有任何进程在跑），用户以为查完了却还显示"在查"。 */
function renderAiBadges(book) {
    // aiVerdicts 正常是 {}；防御成对象，避免状态被意外清成 null 时整页渲染中断
    const verdicts = (state.aiVerdicts || {})[book.bookId] || [];
    /* 同一引擎现在可能有两条结论行并存：整本初筛（model 不带后缀）与逐章校验
       （model 形如 glm-4-flash#verify）。徽章只放一枚，规则：
       ① 逐章命中优先——它是更明确的证据；
       ② 其余情况取时间最新的（逐章查完且非 AI 的结论，比几天前的初筛更可信）。 */
    const picked = new Map();
    for (const verdict of verdicts) {
        if (Number(verdict.aiFlag) === 0) continue; // 进度行不是结论
        const isVerify = String(verdict.model || '').endsWith('#verify');
        const previous = picked.get(verdict.engine);
        if (!previous) {
            picked.set(verdict.engine, verdict);
            continue;
        }
        const prevVerifyHit = String(previous.model || '').endsWith('#verify')
            && Number(previous.aiFlag) === 2;
        const verifyHit = isVerify && Number(verdict.aiFlag) === 2;
        if (prevVerifyHit && !verifyHit) continue;
        if (verifyHit && !prevVerifyHit) {
            picked.set(verdict.engine, verdict);
            continue;
        }
        if (String(verdict.checkedTime || '') >= String(previous.checkedTime || '')) {
            picked.set(verdict.engine, verdict);
        }
    }
    return [...picked.values()].map(verdict => {
        const shortName = aiEngineName(verdict.engine);
        const score = formatAiScore(verdict.aiScore);
        /* 结论徽章本身就是重跑入口：点一下用该引擎 + 当前取样规则重判这本书。
           用 button 而不是 span 才能被键盘聚焦、也才有 disabled 态可锁。
           data-engine 是重跑要用的引擎，data-book-id 让事件委托不必回溯 DOM */
        const rerunHint = '；点一下按当前取样规则重跑该引擎';
        const attrs = `data-action="ai-rerun" data-book-id="${escapeAttr(book.bookId || '')}"`
            + ` data-engine="${escapeAttr(verdict.engine || '')}"`;
        const scoreText = score === '—' ? '' : ` ${score}`;
        if (verdict.aiFlag === 2) {
            return `<button type="button" class="ai-badge ai" ${attrs}
                title="${escapeAttr((verdict.reason || shortName + ' 判为 AI') + rerunHint)}">${shortName}${scoreText}</button>`;
        }
        if (verdict.aiFlag === 3) {
            return `<button type="button" class="ai-badge suspect" ${attrs}
                title="${escapeAttr((verdict.reason || shortName + ' 判为疑似 AI') + rerunHint)}">${shortName}疑似${scoreText}</button>`;
        }
        return `<button type="button" class="ai-badge human" ${attrs}
            title="${escapeAttr(shortName + ' 判为非 AI（风险 ' + score + '）' + rerunHint)}">${shortName}·清${scoreText}</button>`;
    }).join('');
}

/* 详情入口：只有拿到结论的行才显示；结论为空时没有可看的内容 */
function renderAiDetailButton(book) {
    const verdicts = (state.aiVerdicts || {})[book.bookId] || [];
    if (!book.bookId || verdicts.length === 0) return '';
    return `<button type="button" class="ai-detail-btn" data-action="ai-detail"
                    data-book-id="${escapeAttr(book.bookId)}"
                    title="查看每个引擎的 AI 率、结论与判定理由">详情</button>`;
}

/* 逐章校验的进度口径：型号池内各型号已查章数之和 / (章数 × 型号数)。
   渲染按钮与批量校验的实时文案共用这一处——两处各算一遍必然漂移
   （会出现按钮写着 10/10、循环还在继续请求的错位）。 */
export function verifyProgress(bookId, engineId) {
    const status = state.aiStatus || {};
    const pools = status.modelPools || {};
    // 单批章数与后端取样默认一致（前 10 章）；型号池默认只有主型号，用户勾多型号才会成倍
    const perModel = Math.max(1, Number(status.verifyMaxChapters || 10));
    const models = Array.isArray(pools[engineId]) && pools[engineId].length > 0 ? pools[engineId] : [''];
    const total = perModel * models.length;
    // 只统计逐章行（model 带 #verify 后缀）：整本初筛的结论不算校验进度
    const verdicts = (state.aiVerdicts || {})[bookId] || [];
    let done = 0;
    let hit = false;
    for (const v of verdicts) {
        if (v.engine !== engineId || !String(v.model || '').endsWith('#verify')) continue;
        done += Math.min(Number(v.chaptersChecked || 0), perModel);
        if (Number(v.aiFlag) === 2) hit = true;
    }
    return { done: Math.min(done, total), total, hit, perModel };
}

/* 逐章校验按钮：按「已配置 Key 的引擎」动态渲染，配置了几个就显示几个。
   2026-10-22 起可反复点：同一模型同一章节不重复校验，命中 AI 也不再锁死，
   继续把剩余章节查完。进度按「型号池里各型号已查章数之和 / (章数 × 型号数)」展示，
   全部查完才置灰——命中是结论，不该变成「按钮点不动」。
   点一次 = 按取样规则查完一批章节（`runVerifyBatch` 循环调接口），不是一章一点。 */
function renderVerifyButtons(book) {
    if (!book.bookId) return '';
    const status = state.aiStatus || {};
    const engines = status.verifyEngines || [];
    if (engines.length === 0) return '';

    return engines.map(engine => {
        const id = engine.id;
        const shortName = engine.shortName || engine.name || id;
        const { done, total, hit, perModel } = verifyProgress(book.bookId, id);

        if (done >= total) {
            // 型号池里每个模型都查完这批章节：灰掉，避免重复烧额度
            return `<button type="button" class="zq-btn done" disabled data-engine="${escapeAttr(id)}"
                            title="${escapeAttr(shortName)} 的所有模型都已校验完这 ${perModel} 章">${escapeText(shortName)} ${done}/${total}</button>`;
        }
        // 命中过仍可点：红色只表示结论，不是禁用态
        const cls = hit ? 'zq-btn hit' : 'zq-btn';
        const label = hit ? `${shortName}·AI ${done}/${total}` : `${shortName} ${done}/${total}`;
        return `<button type="button" class="${cls}" data-action="verify"
                        data-book-id="${escapeAttr(book.bookId)}" data-engine="${escapeAttr(id)}"
                        title="逐章校验（${escapeAttr(shortName)}）：点一次按当前取样规则查完一批章节（默认前 10 章），同一模型同一章不重复；命中后仍会查完。型号池里有几个型号就点几次">${escapeText(label)}</button>`;
    }).join('');
}

export function renderBooks(data) {
    // 没有响应体（首屏还没加载完）时保持现有列表不动：翻页/改每页条数这类
    // 本地重绘都带 state.lastData，真拿到 null 说明调用方传错了，
    // 此时抛 TypeError 会被加载链的 catch 吞成“加载失败”，掩盖真实原因
    if (!data) return;
    const listEl = document.getElementById('bookList');
    state.lastData = data; // 翻页/改每页条数要直接重画这份响应
    const all = Array.isArray(data.data) ? data.data : [];

    /* 分页只切本地数组：数据已经在这份响应里，翻页不该再打后端 */
    const size = getEffectivePageSize();
    const pages = Math.max(1, Math.ceil(all.length / size));
    if (state.currentPage > pages) {
        state.currentPage = pages; // 页码越界（比如数据变少）钳到最后一页
    }
    const from = (state.currentPage - 1) * size;
    const novels = all.slice(from, from + size);

    document.getElementById('totalCount').textContent = all.length;

    const catName = categoryDisplayName(state.currentCategory);
    if (catName) {
        document.getElementById('currentCategory').textContent = catName;
    }

    const blockedMode = state.currentCategory === 'ALL' && state.blockedView;
    /* 多选只在「全部」视图出现：分类视图的行没有拉黑按钮，批量操作无从谈起 */
    /* 勾选框在所有视图都可用：2026-10-22 起「校验选中」是全局动作，
       只在「全部」视图给方框会让分类视图里的这个按钮永远点不动。
       批量条同理由 CSS 一并显示，拉黑/恢复走的是单本接口，与视图无关 */
    const selectable = true;
    if (novels.length === 0) {
        if (blockedMode) {
            renderEmptyState('🚫', '没有被拉黑的书', '取消勾选“只看已拉黑”即可回到正常列表');
            syncBatchBar();
            return;
        }
        let detail = '该分类本次没有返回数据';
        if (state.currentCategory === 'ALL') {
            // 筛选到 0 行、当前榜单分区为空、库里真没书，三种提示要分开
            if (state.filtersActive) {
                detail = '没有符合当前筛选条件的书，试试放宽字数或状态';
            } else if (data.dbEmpty === false) {
                // 老后端没有 dbEmpty 字段时按旧文案走，只有明确 false 才说明是空分区
                const rt = findRankType(getActiveRankType());
                detail = `「${rt ? rt.name : '当前榜单'}」还没有抓取过数据，可点“一键获取全部”，或进任一分类抓取`;
            } else {
                detail = '数据库里还没有数据，可先点“一键获取全部”';
            }
        }
        renderEmptyState('📭', '暂无数据', detail);
        syncBatchBar();
        return;
    }

    const saveHint = data.dbSaved === false
        ? `<div class="db-hint">⚠️ 数据库不可用，本次结果未落库，“全部”视图不会更新</div>`
        : '';

    listEl.innerHTML = saveHint + novels.map((book, index) => {
        const position = from + index + 1;
        /* 分类视图显示上游榜单名次；“全部”视图是热度序/拉黑时间序，只能显示当前序号 */
        const boardRank = state.currentCategory === 'ALL' ? NaN : parseInt(book.rank, 10);
        const rank = isNaN(boardRank) ? position : boardRank;
        const rankTitle = state.currentCategory === 'ALL'
            ? (blockedMode ? '按拉黑时间倒序' : '按在读人数排序')
            : '上游榜单名次';
        let rankClass = 'rank';
        if (rank === 1) rankClass += ' top1';
        else if (rank === 2) rankClass += ' top2';
        else if (rank === 3) rankClass += ' top3';

        const bookId = book.bookId ? String(book.bookId) : '';
        const statusClass = book.status === '已完结' ? 'finished' : '';
        const title = escapeText(book.title || '未知书名');

        // bookId 缺失时不生成外链和拉黑按钮，否则会指向错误页面 / 打到空主键上
        const titleHtml = bookId
            ? `<a href="https://fanqienovel.com/page/${escapeAttr(bookId)}" target="_blank" rel="noopener noreferrer">${title}</a>`
            : title;
        /* 行内右侧簇：AI 判定徽章 + 拉黑 + 朱雀。
           标题行左侧只留“这本书是什么”（书名 / 状态），右侧是“判定结论 + 能对它做什么”。
           徽章跟着按钮走，是因为它本来就是右侧这几个按钮跑出来的结果——
           判定按钮和判定结论分居行首行尾，扫读时要来回横跳 */
        const actionsHtml = titleActionsHtml(book, blockedMode);

        return `
            <div class="book-item"${bookId ? ` data-book-id="${escapeAttr(bookId)}"` : ''}>
                ${selectable && bookId
                    ? `<input type="checkbox" class="row-check" data-book-id="${escapeAttr(bookId)}"
                              aria-label="选择《${escapeAttr(book.title || '未知书名')}》以批量操作">`
                    : ''}
                <div class="${rankClass}" title="${escapeAttr(rankTitle)}">${rank}</div>
                ${renderCover(book)}
                <div class="content">
                    <div class="title font-DNMrHsV173Pd4pgy">
                        ${titleHtml}
                        <span class="status ${statusClass}">${escapeText(book.status || '连载中')}</span>
                        ${actionsHtml ? `<span class="title-actions">${actionsHtml}</span>` : ''}
                    </div>
                    <div class="info-row">
                        <span class="author font-DNMrHsV173Pd4pgy">${escapeText(book.author || '未知作者')}</span>
                        ${renderChips(book)}
                        <span class="chapter font-DNMrHsV173Pd4pgy"
                              title="${escapeAttr(book.lastChapter || '暂无章节信息')}">📌 ${escapeText(book.lastChapter || '暂无章节信息')}</span>
                    </div>
                    <div class="desc font-DNMrHsV173Pd4pgy">
                        ${escapeText(book.description || '暂无简介')}
                    </div>
                </div>
            </div>
        `;
    }).join('') + renderPager(pages);
    syncBatchBar();
}

/* ---------- 当前页多选与批量条 ----------
   syncBatchBar 是行内方框与批量条的唯一同步点：每次整页渲染后、每次勾选变更后都调。
   它顺手把 selection 裁到当前页——翻页/换分类/重新筛选后旧选择自然失效，
   不需要在每个切换入口手动清空（漏一个就会把看不见的书一起拉黑） */
export function syncBatchBar() {
    const checks = Array.from(document.querySelectorAll('#bookList .row-check'));
    const visible = new Set(checks
        .map(el => String(el.getAttribute('data-book-id') || ''))
        .filter(Boolean));
    const kept = new Set();
    for (const id of state.selection) {
        if (visible.has(id)) kept.add(id);
    }
    state.selection = kept;

    checks.forEach(el => {
        const input = /** @type {HTMLInputElement} */ (el);
        const id = String(input.dataset.bookId || '');
        input.checked = state.selection.has(id);
        const row = input.closest('.book-item');
        if (row) row.classList.toggle('selected', input.checked);
    });

    const bar = document.getElementById('batchBar');
    if (!bar) return;
    bar.hidden = visible.size === 0;
    /* 「校验选中」按钮跟着选中数走：没人选就禁用（点不动比点了没反应清楚），
       数量放进独立的小徽标而不是按钮文字——执行中 setBtnState 会改写 .btn-text，
       把数量拼在文字里会在校验中途消失 */
    const selectBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('aiPageBtn'));
    if (selectBtn) {
        const count = state.selection.size;
        selectBtn.disabled = count === 0;
        const badge = /** @type {HTMLElement|null} */ (selectBtn.querySelector('.btn-count'));
        if (badge) {
            badge.textContent = count > 0 ? String(count) : '';
            badge.hidden = count === 0;
        }
    }
    setBatchProgress(0, 0);
}

/* 批量执行进度：total=0 表示空闲，按钮可用性按已选数量决定；
   执行中禁用全部批量按钮，避免连点叠出多轮请求 */
export function setBatchProgress(done, total) {
    const bar = document.getElementById('batchBar');
    if (!bar) return;
    const busy = total > 0;
    const count = bar.querySelector('.batch-count');
    if (count) {
        count.textContent = busy
            ? (done >= total ? `已完成 ${total} 本` : `处理中 ${done}/${total} 本`)
            : (state.selection.size > 0 ? `已选 ${state.selection.size} 本` : '勾选左侧方框可批量操作当前页');
    }
    const hasAny = state.selection.size > 0;
    bar.querySelectorAll('[data-action^="batch-"]').forEach(el => {
        const btn = /** @type {HTMLButtonElement} */ (el);
        btn.disabled = busy || !hasAny;
    });
    const all = /** @type {HTMLInputElement|null} */ (document.getElementById('batchAll'));
    if (all) {
        const rows = document.querySelectorAll('#bookList .row-check').length;
        all.disabled = busy || rows === 0;
        all.checked = hasAny && state.selection.size === rows;
    }
}

/* 行内右侧操作簇（AI 徽章 + 详情 + 逐章校验按钮 + 拉黑按钮）。
   整页渲染与单行原地更新（patchBookRow）共用，保证两处结构永远一致。
   拉黑排在最后：它是行内唯一的破坏性写操作，放行尾与「详情」分开，
   扫过行时不会在点「校验」的同一段距离上误触（二段确认是第二道保险，不是第一道） */
function titleActionsHtml(book, blockedMode) {
    const bookId = book.bookId ? String(book.bookId) : '';
    // 已拉黑视图里的行是来“恢复”的，正常视图里才是“拉黑”
    const blockHtml = (state.currentCategory === 'ALL' && bookId)
        ? `<button class="block-btn ${blockedMode ? 'restore' : ''}"
                       data-action="${blockedMode ? 'unblock' : 'block'}"
                       data-book-id="${escapeAttr(bookId)}">${blockedMode ? '恢复' : '拉黑'}</button>`
        : '';
    return renderAiBadges(book) + renderAiDetailButton(book) + renderVerifyButtons(book) + blockHtml;
}

/* 单行原地更新：只替换该书的操作簇（徽章/按钮），不再整页重建 innerHTML——
   全量重建会把封面 <img> 销毁重载，逐本判定时整页闪一遍。
   行不在当前页（翻页后/被筛选隐藏）时静默跳过 */
export function patchBookRow(bookId) {
    if (!state.lastData || !Array.isArray(state.lastData.data) || !bookId) return;
    const target = String(bookId);
    const book = state.lastData.data.find(item => String(item.bookId) === target);
    if (!book) return;
    const row = document.querySelector(`#bookList .book-item[data-book-id="${CSS.escape(target)}"]`);
    if (!row) return;
    const blockedMode = state.currentCategory === 'ALL' && state.blockedView;
    const html = titleActionsHtml(book, blockedMode);
    const container = row.querySelector('.title-actions');
    if (container) {
        if (html) {
            container.innerHTML = html;
        } else {
            container.remove();
        }
        return;
    }
    // 行内还没有操作簇（此前无任何结论）：这次有了就补插进标题行
    if (html) {
        const titleEl = row.querySelector('.title');
        if (titleEl) {
            titleEl.insertAdjacentHTML('beforeend', `<span class="title-actions">${html}</span>`);
        }
    }
}

/* 分页条：首页/上一页 + 页码窗口（1 与末页恒显，缺口画省略号）+ 下一页/末页 + 跳页输入框。
   只有一页时不显示；页码点击仍走 .pager-btn[data-page] 委托，跳页按钮走 data-action */
function renderPager(pages) {
    if (pages <= 1) return '';
    const btn = (label, page, disabled) =>
        `<button class="pager-btn" data-page="${page}"${disabled ? ' disabled' : ''}>${label}</button>`;
    const numBtn = (page) =>
        `<button class="pager-btn pager-num${page === state.currentPage ? ' active' : ''}"
                 data-page="${page}"${page === state.currentPage ? ' aria-current="page"' : ''}>${page}</button>`;
    const parts = [
        btn('首页', 1, state.currentPage === 1),
        btn('上一页', state.currentPage - 1, state.currentPage === 1)
    ];

    /* 页数少就全铺页码；多则只留 1、末页与当前页 ±1，中间缺口用省略号占位 */
    let nums;
    if (pages <= 7) {
        nums = Array.from({ length: pages }, (_, i) => i + 1);
    } else {
        nums = [...new Set([1, pages, state.currentPage - 1, state.currentPage, state.currentPage + 1])]
            .filter(n => n >= 1 && n <= pages)
            .sort((a, b) => a - b);
    }
    let prev = 0;
    nums.forEach(n => {
        if (n - prev > 1) parts.push('<span class="pager-ellipsis" aria-hidden="true">…</span>');
        parts.push(numBtn(n));
        prev = n;
    });

    parts.push(
        btn('下一页', state.currentPage + 1, state.currentPage === pages),
        btn('末页', pages, state.currentPage === pages),
        `<span class="pager-info">第 ${state.currentPage} / ${pages} 页</span>`,
        `<span class="pager-jump">
            <label for="pageJumpInput">跳至</label>
            <input type="number" id="pageJumpInput" class="input" min="1" max="${pages}"
                   placeholder="页码" aria-label="跳转到指定页">
            <span>页</span>
            <button type="button" class="pager-btn" data-action="page-jump">跳转</button>
        </span>`
    );
    return `<div class="pager">${parts.join('')}</div>`;
}

/* ---------- toast（取代 alert；多条并存，互不打断） ---------- */

/* type: '' | 'success' | 'error' | 'warn'；多行文案里的 \n 靠 CSS 的
   white-space:pre-line 折行，textContent 顺带解决转义。
   action 可选：给「撤销」这类一步就能反悔的操作挂一个按钮（拉黑后 6 秒内可撤销） */
/**
 * @param {string} message
 * @param {string} [type]
 * @param {number} [duration]
 * @param {{label: string, onClick: () => void}} [action]
 */
export function showToast(message, type = '', duration = 3000, action) {
    const host = document.getElementById('toastHost');
    if (!host) return;
    const toast = document.createElement('div');
    toast.className = type ? `toast ${type}` : 'toast';

    const body = document.createElement('span');
    body.className = 'toast-body';
    body.textContent = message;
    toast.appendChild(body);

    let timer = 0;
    const dismiss = () => {
        clearTimeout(timer);
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300); // 等退场动画走完再摘节点
    };

    if (action && action.label) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'toast-action';
        btn.textContent = action.label;
        btn.addEventListener('click', () => {
            try {
                action.onClick();
            } finally {
                dismiss();
            }
        });
        toast.appendChild(btn);
    }

    host.appendChild(toast);
    // 先让节点以无 .show 的状态参与一次布局，再加类才能触发过渡动画
    void toast.offsetWidth;
    toast.classList.add('show');
    timer = window.setTimeout(dismiss, duration);
}


/* 按钮的图标与文字是拆开的两个 span（原因见 index.html 里的注释：emoji 字形
   绘制宽度常大于布局宽度，合在一个文本节点里会压住汉字）。直接写 textContent
   会把 span 冲掉，所以改文案一律走这里，保持结构与初始一致 */
export function setBtnState(btn, icon, text) {
    if (!btn) return;
    const ico = btn.querySelector('.btn-ico');
    const label = btn.querySelector('.btn-text');
    if (ico && label) {
        ico.textContent = icon;
        label.textContent = text;
    } else {
        btn.textContent = `${icon} ${text}`;
    }
}
