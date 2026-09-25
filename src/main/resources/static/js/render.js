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

/* 导航：全部在最前，之后按 男频 / 女频 两组铺 displayed 的分类。
   男女频存在同名分类（如“科幻末世”），分组标签让归属一眼可辨 */
export function renderNav() {
    const nav = document.getElementById('navInner');
    if (!nav) return;
    const btn = (code, name) =>
        `<button class="nav-btn ${code === state.currentCategory ? 'active' : ''}"
                 data-code="${escapeAttr(code)}">${escapeText(name)}</button>`;
    const parts = [btn('ALL', '全部')];
    getDisplayedGroups().forEach(group => {
        if (group.items.length === 0) return;
        parts.push(`<span class="nav-group-label">${escapeText(group.label)}</span>`);
        group.items.forEach(cat => parts.push(btn(cat.code, cat.name)));
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
        input.disabled = disabled;
    });
}

/* 统计栏的榜单名随 meta 走；meta 未就绪时保持 HTML 里的占位符 */
export function updateStatsMeta() {
    const el = document.getElementById('currentRankType');
    if (!el) return;
    const rt = findRankType(getActiveRankType());
    el.textContent = rt ? rt.name : '—';
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
    const allCheckbox = document.getElementById('checkAll');
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
        ['📖', book.readCount ? book.readCount + ' 人在读' : ''],
        ['📝', book.wordCount || ''],
        ['🏷️', book.categoryName || ''],
        ['🕐', book.updateTime ? '更新于 ' + book.updateTime : '']
    ].filter(item => item[1])
        .map(item => `<span class="chip"><i>${item[0]}</i>${escapeText(item[1])}</span>`)
        .join('');
}

export function renderBooks(data) {
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
    if (novels.length === 0) {
        if (blockedMode) {
            renderEmptyState('🚫', '没有被拉黑的书', '取消勾选“只看已拉黑”即可回到正常列表');
            return;
        }
        let detail = '该分类本次没有返回数据';
        if (state.currentCategory === 'ALL') {
            // 筛选到 0 行和库里真没书是两回事，提示要分开
            detail = state.filtersActive
                ? '没有符合当前筛选条件的书，试试放宽字数或状态'
                : '数据库里还没有数据，可先点“一键获取全部”';
        }
        renderEmptyState('📭', '暂无数据', detail);
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
        // 已拉黑视图里的行是来“恢复”的，正常视图里才是“拉黑”
        const blockHtml = (state.currentCategory === 'ALL' && bookId)
            ? `<button class="block-btn ${blockedMode ? 'restore' : ''}"
                       data-action="${blockedMode ? 'unblock' : 'block'}"
                       data-book-id="${escapeAttr(bookId)}">${blockedMode ? '恢复' : '拉黑'}</button>`
            : '';

        return `
            <div class="book-item">
                <div class="${rankClass}" title="${escapeAttr(rankTitle)}">${rank}</div>
                ${renderCover(book)}
                <div class="content">
                    <div class="title font-DNMrHsV173Pd4pgy">
                        ${titleHtml}
                        <span class="status ${statusClass}">${escapeText(book.status || '连载中')}</span>
                        ${blockHtml}
                    </div>
                    <div class="info-row">
                        <span class="author font-DNMrHsV173Pd4pgy">${escapeText(book.author || '未知作者')}</span>
                        ${renderChips(book)}
                    </div>
                    <div class="desc font-DNMrHsV173Pd4pgy">
                        ${escapeText(book.description || '暂无简介')}
                    </div>
                    <div class="meta font-DNMrHsV173Pd4pgy">
                        📌 ${escapeText(book.lastChapter || '暂无章节信息')}
                    </div>
                </div>
            </div>
        `;
    }).join('') + renderPager(pages);
}

/* 只有一页时不显示分页条 */
function renderPager(pages) {
    if (pages <= 1) return '';
    const btn = (label, page, disabled) =>
        `<button class="pager-btn" data-page="${page}"${disabled ? ' disabled' : ''}>${label}</button>`;
    return `
        <div class="pager">
            ${btn('首页', 1, state.currentPage === 1)}
            ${btn('上一页', state.currentPage - 1, state.currentPage === 1)}
            <span class="pager-info">第 ${state.currentPage} / ${pages} 页</span>
            ${btn('下一页', state.currentPage + 1, state.currentPage === pages)}
            ${btn('末页', pages, state.currentPage === pages)}
        </div>
    `;
}

/* ---------- toast（取代 alert；多条并存，互不打断） ---------- */

/* type: '' | 'success' | 'error' | 'warn'；多行文案里的 \n 靠 CSS 的
   white-space:pre-line 折行，textContent 顺带解决转义。 */
export function showToast(message, type = '', duration = 3000) {
    const host = document.getElementById('toastHost');
    if (!host) return;
    const toast = document.createElement('div');
    toast.className = type ? `toast ${type}` : 'toast';
    toast.textContent = message;
    host.appendChild(toast);
    // 先让节点以无 .show 的状态参与一次布局，再加类才能触发过渡动画
    void toast.offsetWidth;
    toast.classList.add('show');
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300); // 等退场动画走完再摘节点
    }, duration);
}
