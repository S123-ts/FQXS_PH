// @ts-check
/* ============================================================
   js/events.js - 事件绑定
   原则：除 document 级监听外不在动态节点上挂监听器，
   动态元素一律靠 data-* + 事件委托识别
   ============================================================ */

import { state, saveShownPreference, saveMinRating } from './state.js';
import { getDisplayedCategories } from './meta.js';
import {
    renderNav, updateCheckAllState, updateDropdownLabel,
    initChromeUi, toggleChromeCollapse, updateChromeSummary, syncBatchBar
} from './render.js';
import {
    loadData, fetchAllData, goToPage, jumpToPageFromInput, onFilterChange,
    onPageSizeChange, setBlocked, setWatched, switchCategory, saveRankType, saveDisplayCategories,
    saveFetchGender, runBatchBlock, enrichBookMeta
} from './data.js';
import { runAiSweep, runSelectionVerdict, runBadgeRerun, runVerifyBatch } from './ai_actions.js';
import {
    openConfigPanel, closeConfigPanel, isConfigPanelOpen,
    trapFocusInConfigPanel, getConfigDraftCodes, onConfigItemToggle,
    setGroupChecked
} from './render_config.js';
import {
    openAiKeyPanel, closeAiKeyPanel, isAiKeyPanelOpen, trapFocusInAiKeyPanel,
    saveAiKeys, toggleClearKey, onModelPoolChange, toggleKeyRow,
    testEngine, removeEngine, addEngine, discoverModels, expandModelList, toggleEngine
} from './ai_config.js';
import {
    openAiDetail, closeAiDetail, isAiDetailOpen, trapFocusInAiDetail
} from './ai_detail.js';
import { logoutApi } from './api.js';

// 下拉开合状态只归本模块管
let isDropdownOpen = false;

/* 拉黑确认态：第一次点击只把按钮变成「确认拉黑?」，3 秒内不再点就复位。
   行内按钮在整页重绘后会被替换，定时器只操作已丢弃的节点，无副作用。
   ——为什么不在 data 层做：这是纯交互节流，不该让控制器知道“点了两次” */
let blockConfirmBtn = null;
let blockConfirmTimer = 0;

function resetBlockConfirm() {
    if (blockConfirmBtn) {
        blockConfirmBtn.classList.remove('confirm');
        blockConfirmBtn.removeAttribute('data-confirm');
        blockConfirmBtn.textContent = '拉黑';
    }
    clearTimeout(blockConfirmTimer);
    blockConfirmBtn = null;
}

function requestBlockConfirm(btn) {
    resetBlockConfirm();
    blockConfirmBtn = btn;
    btn.classList.add('confirm');
    btn.dataset.confirm = '1';
    btn.textContent = '确认拉黑?';
    blockConfirmTimer = window.setTimeout(resetBlockConfirm, 3000);
}

export function bindEvents() {
    /* ---------- click 全量委托到 document：
       导航/翻页/拉黑/刷新/一键获取/下拉开关/设置面板 ---------- */
    document.addEventListener('click', (e) => {
        if (!(e.target instanceof Element)) return;

        // 点到别处就放弃这次拉黑确认（避免“确认态”一直亮着，下一次误点直接生效）
        if (blockConfirmBtn && !blockConfirmBtn.contains(e.target)) {
            resetBlockConfirm();
        }

        // 行内「更多」菜单是临时的：点菜单外面任意位置都收起
        if (!e.target.closest('.row-more')) {
            closeRowMenus();
        }

        // 手机摘要条：只切换控制区展开/收起，不触碰筛选本身
        if (e.target.closest('#chromeSummary')) {
            toggleChromeCollapse();
            return;
        }

        const toggle = e.target.closest('.dropdown-toggle');
        if (toggle) {
            toggleDropdown();
            return;
        }

        const navBtn = /** @type {HTMLElement} */ (e.target.closest('.nav-btn[data-code]'));
        if (navBtn) {
            switchCategory(navBtn.dataset.code);
            return;
        }

        const pagerBtn = /** @type {HTMLElement} */ (e.target.closest('.pager-btn[data-page]'));
        if (pagerBtn) {
            goToPage(parseInt(pagerBtn.dataset.page, 10));
            return;
        }

        // 跳页按钮没有 data-page，不会被上面的分支拦走
        const jumpBtn = e.target.closest('[data-action="page-jump"]');
        if (jumpBtn) {
            jumpToPageFromInput();
            return;
        }

        const blockBtn = /** @type {HTMLElement} */ (e.target.closest('.block-btn'));
        if (blockBtn) {
            // data-action 是 block / unblock，在这里读成布尔
            const blocked = blockBtn.dataset.action !== 'unblock';
            if (blocked && blockBtn.dataset.confirm !== '1') {
                requestBlockConfirm(blockBtn);
                return;
            }
            resetBlockConfirm();
            setBlocked(blockBtn.dataset.bookId, blocked, blockBtn);
            return;
        }

        // 点在遮罩空白处（而非对话框内部）同样视为关闭
        if (e.target.id === 'configMask') {
            closeConfigPanel();
            return;
        }
        if (e.target.id === 'aiKeyMask') {
            closeAiKeyPanel();
            return;
        }
        if (e.target.id === 'aiDetailMask') {
            closeAiDetail();
            return;
        }

        const actionBtn = /** @type {HTMLElement} */ (e.target.closest('[data-action]'));
        if (actionBtn) {
            switch (actionBtn.dataset.action) {
                case 'refresh':
                    loadData();
                    break;
                case 'fetch-all':
                    fetchAllData();
                    break;
                // 关注：与拉黑同构的写操作，但不设二段确认——误点再点一下即取消
                case 'watch':
                    setWatched(actionBtn.dataset.bookId, true, actionBtn);
                    break;
                case 'unwatch':
                    setWatched(actionBtn.dataset.bookId, false, actionBtn);
                    break;
                // 批量拉黑/恢复：对当前页已勾选的书串行调用单本接口
                case 'batch-block':
                    runBatchBlock(true);
                    break;
                case 'batch-unblock':
                    runBatchBlock(false);
                    break;
                case 'batch-clear':
                    state.selection = new Set();
                    syncBatchBar();
                    break;
                // 免费初筛：limit=0 交给后端取配置默认值，引擎用当前下拉选择
                case 'ai-sweep':
                    runAiSweep(0, state.currentEngine, { interactive: true });
                    break;
                // 校验选中：用所选免费引擎逐个判定已勾选的书（单选或多选）
                case 'ai-page':
                    runSelectionVerdict();
                    break;
                // 点行内模型名（徽章）：用该引擎 + 当前取样规则强制重跑这本书，结果写一条 rerun 流水
                case 'ai-rerun':
                    runBadgeRerun(actionBtn.dataset.bookId, actionBtn.dataset.engine, actionBtn);
                    break;
                // 逐章校验：对任意已配置引擎（GLM / ERNIE / Qwen）按当前取样规则一次跑完整批章节，
                // 不再一章一点。按钮自身带 data-engine，进度与命中状态按引擎+型号存在明细表
                case 'verify':
                    runVerifyBatch(actionBtn.dataset.bookId, actionBtn.dataset.engine, actionBtn);
                    break;
                case 'open-config':
                    openConfigPanel(actionBtn);
                    break;
                // 密钥面板：配置入口与保存/清除，逻辑都在 ai_config.js
                case 'open-ai-keys':
                    openAiKeyPanel(actionBtn);
                    break;
                // 每行「详情」：弹出该书的逐引擎 AI 率与理由
                case 'ai-detail':
                    openAiDetail(actionBtn.dataset.bookId, actionBtn);
                    break;
                // 行内「更多」：展开详情与逐章校验，同一时刻只开一个
                case 'row-more':
                    toggleRowMenu(actionBtn);
                    break;
                case 'ai-detail-close':
                    closeAiDetail();
                    break;
                case 'ai-key-close':
                case 'ai-key-cancel':
                    closeAiKeyPanel();
                    break;
                case 'ai-key-save':
                    saveAiKeys();
                    break;
                case 'ai-key-clear':
                    toggleClearKey(actionBtn.dataset.engine);
                    break;
                // 未配置的引擎默认收起，点「配置」展开
                case 'ai-key-expand':
                    toggleKeyRow(actionBtn.dataset.engine, actionBtn);
                    break;
                case 'ai-engine-test':
                    testEngine(actionBtn.dataset.engine, actionBtn);
                    break;
                case 'ai-engine-toggle':
                    toggleEngine(actionBtn.dataset.engine, actionBtn);
                    break;
                case 'ai-engine-discover':
                    discoverModels(actionBtn.dataset.engine, actionBtn);
                    break;
                case 'ai-model-expand':
                    expandModelList(actionBtn);
                    break;
                case 'ai-engine-delete':
                    removeEngine(actionBtn.dataset.engine, actionBtn);
                    break;
                case 'ai-engine-add':
                    addEngine();
                    break;
                case 'config-close':
                case 'config-cancel':
                    closeConfigPanel();
                    break;
                case 'config-save':
                    saveDisplayCategories(getConfigDraftCodes());
                    break;
                case 'group-all':
                    setGroupChecked(parseInt(actionBtn.dataset.gender, 10), true);
                    break;
                case 'group-clear':
                    setGroupChecked(parseInt(actionBtn.dataset.gender, 10), false);
                    break;
                // 退出登录：会话销毁后整页回登录页（服务端会话已没了，留在原页只会全是 401）
                case 'logout':
                    logout();
                    break;
            }
            // 菜单项点完就收起：详情已弹窗、校验已开始，菜单留着只会挡住下一行
            if (actionBtn.closest('.more-menu')) {
                closeRowMenus();
            }
        }

        // 任何一次点击都可能改了「看什么」（导航/榜单），摘要统一在委托末尾对齐
        updateChromeSummary();
    });

    /* 点击下拉外部就收起 —— 独立的 document 级监听 */
    document.addEventListener('click', (e) => {
        const container = document.getElementById('dropdownMulti');
        if (container && !container.contains(/** @type {Node} */ (e.target))) {
            closeDropdown();
        }
    });

    /* ---------- 键盘：Esc 关设置面板/下拉，面板打开期间 Tab 圈定焦点 ---------- */
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (document.querySelector('.row-more.menu-open')) {
                // 菜单不是模态，Esc 优先收菜单，第二次才关弹窗
                e.preventDefault();
                closeRowMenus();
            } else if (isAiDetailOpen()) {
                e.preventDefault();
                closeAiDetail();
            } else if (isAiKeyPanelOpen()) {
                e.preventDefault();
                closeAiKeyPanel();
            } else if (isConfigPanelOpen()) {
                e.preventDefault();
                closeConfigPanel();
            } else if (isDropdownOpen) {
                // 下拉同样支持 Esc 收起，键盘用户不用摸鼠标
                closeDropdown();
            }
        } else if (e.key === 'Tab' && isAiDetailOpen()) {
            trapFocusInAiDetail(e);
        } else if (e.key === 'Tab' && isAiKeyPanelOpen()) {
            trapFocusInAiKeyPanel(e);
        } else if (e.key === 'Tab' && isConfigPanelOpen()) {
            trapFocusInConfigPanel(e);
        } else if (e.key === 'Enter' && e.target instanceof Element
            && e.target.id === 'pageJumpInput') {
            // 跳页输入框里回车等同点「跳转」；分页条是动态节点，Enter 也走委托
            e.preventDefault();
            jumpToPageFromInput();
        } else {
            handleListShortcut(e);
        }
    });

    /* ---------- 静态控件直接绑 change / input ---------- */
    // #dropdownMenu 本身是静态节点：菜单里动态生成的 checkbox 变化委托到它上面
    document.getElementById('dropdownMenu').addEventListener('change', (e) => {
        const input = e.target;
        if (!(input instanceof HTMLInputElement) || input.type !== 'checkbox') return;
        if (input.id === 'checkAll') {
            toggleAllCategories(input.checked);
        } else if (input.dataset.cat !== undefined) {
            onCategoryToggle(input.dataset.cat, input.checked);
        }
    });

    // #configBody 也是静态节点：面板里的分类 checkbox 变化委托上来
    document.getElementById('configBody').addEventListener('change', (e) => {
        const input = e.target;
        if (!(input instanceof HTMLInputElement) || input.type !== 'checkbox') return;
        onConfigItemToggle(input.dataset.catCode, input.checked);
    });

    // 密钥面板的型号勾选：变化即保存（独立于 Key 的「保存」按钮）
    document.getElementById('aiKeyBody').addEventListener('change', (e) => {
        const check = e.target;
        if (!(check instanceof HTMLInputElement) || !check.classList.contains('key-model-check')) return;
        onModelPoolChange(check.dataset.engine, check);
    });

    // 榜单类型分段单选：radio 的 change 冒泡到静态容器 #rankTypeGroup
    document.getElementById('rankTypeGroup').addEventListener('change', (e) => {
        const input = e.target;
        if (input instanceof HTMLInputElement && input.name === 'rankType') {
            saveRankType(input.value);
        }
    });

    // 拉取频道分段单选：只改批量拉取范围，与当前展示无关
    document.getElementById('fetchGenderGroup').addEventListener('change', (e) => {
        const input = e.target;
        if (input instanceof HTMLInputElement && input.name === 'fetchGender') {
            saveFetchGender(input.value);
        }
    });

    // 评分下限：change（回车/失焦）时归一化并记住，留空表示不筛
    document.getElementById('filterMinRating').addEventListener('change', (e) => {
        const input = e.target;
        if (!(input instanceof HTMLInputElement)) return;
        const raw = input.value.trim();
        let normalized = '';
        if (raw !== '') {
            const value = Number(raw);
            // 非数字/越界一律落回默认 9.0，避免把空条件或 999 发给后端
            normalized = Number.isFinite(value)
                ? String(Math.min(10, Math.max(0, Math.round(value * 10) / 10)))
                : '9.0';
        }
        input.value = normalized;
        saveMinRating(normalized);
        onFilterChange();
    });
    document.getElementById('filterWord').addEventListener('change', onFilterChange);
    document.getElementById('filterStatus').addEventListener('change', onFilterChange);
    document.getElementById('filterAuthor').addEventListener('change', onFilterChange);
    document.getElementById('filterBlocked').addEventListener('change', onFilterChange);
    document.getElementById('filterWatched').addEventListener('change', onFilterChange);
    document.getElementById('pageSizeSelect').addEventListener('change', onPageSizeChange);
    document.getElementById('enrichMetaBtn').addEventListener('click', () => {
        enrichBookMeta();
    });

    // 筛选/榜单控件的值变化后，折叠摘要上的文案跟着走（change 会冒泡到 document）
    document.addEventListener('change', updateChromeSummary);

    /* 当前页多选：行首方框与批量条的「全选本页」都走委托。
       选择状态只在 state.selection 里，DOM 只是投影——syncBatchBar 负责对齐两边 */
    document.addEventListener('change', (e) => {
        if (!(e.target instanceof HTMLInputElement)) return;

        if (e.target.classList.contains('row-check')) {
            const id = String(e.target.dataset.bookId || '');
            if (!id) return;
            if (e.target.checked) {
                state.selection.add(id);
            } else {
                state.selection.delete(id);
            }
            syncBatchBar();
            return;
        }

        if (e.target.id === 'batchAll') {
            const ids = Array.from(document.querySelectorAll('#bookList .row-check'))
                .map(el => String(el.getAttribute('data-book-id') || ''))
                .filter(Boolean);
            state.selection = new Set(e.target.checked ? ids : []);
            syncBatchBar();
        }
    });

    // 免费引擎下拉：选项由 loadAiStatus 按可用引擎填充，这里只记选择
    const aiEngineSelect = /** @type {HTMLSelectElement} */ (document.getElementById('aiEngineSelect'));
    if (aiEngineSelect) {
        aiEngineSelect.addEventListener('change', () => {
            state.currentEngine = aiEngineSelect.value;
        });
    }

    const customPageSize = /** @type {HTMLInputElement} */ (document.getElementById('customPageSize'));
    if (customPageSize) {
        // 兜底摆一次显隐：不管 HTML 初始有没有带 hidden，都按 select 当前值对齐
        customPageSize.hidden = /** @type {HTMLSelectElement} */ (document.getElementById('pageSizeSelect')).value !== 'custom';
        // 数字框每敲一个字符就重切本地分页，这是刻意行为，不要改成 change
        customPageSize.addEventListener('input', onPageSizeChange);
    }

    /* ---------- 捕获阶段接管 <img> 加载失败 ----------
       error 事件不冒泡，必须用捕获才能拦到；替代旧版内联 onerror。 */
    document.addEventListener('error', (e) => {
        // 只处理图片，别误伤 script/css 等其他资源的失败
        const target = /** @type {HTMLImageElement} */ (e.target);
        if (target && target.tagName === 'IMG') {
            target.remove(); // 露出底下的 📖 占位
        }
    }, true);

    // 手机折叠摘要条：按会话恢复展开态、量导航高度供吸顶使用
    initChromeUi();
}

/* ---------- 行内「更多」菜单 ----------
   同一时刻只开一个；打开时给所在卡片抬 z-index，否则菜单会被下一张卡片盖住
   （.book-item 是 position:relative，但没有自己的层叠上下文）。 */
function closeRowMenus(except) {
    document.querySelectorAll('.row-more.menu-open').forEach(el => {
        if (el === except) return;
        el.classList.remove('menu-open');
        const menu = /** @type {HTMLElement|null} */ (el.querySelector('.more-menu'));
        if (menu) menu.hidden = true;
        const btn = el.querySelector('.more-btn');
        if (btn) btn.setAttribute('aria-expanded', 'false');
        const row = el.closest('.book-item');
        if (row) row.classList.remove('menu-open');
    });
}

function toggleRowMenu(btn) {
    const holder = btn.closest('.row-more');
    if (!holder) return;
    const menu = /** @type {HTMLElement|null} */ (holder.querySelector('.more-menu'));
    if (!menu) return;
    const willOpen = menu.hidden;
    closeRowMenus(holder);
    menu.hidden = !willOpen;
    holder.classList.toggle('menu-open', willOpen);
    btn.setAttribute('aria-expanded', String(willOpen));
    const row = holder.closest('.book-item');
    if (row) row.classList.toggle('menu-open', willOpen);
}

/* ---------- 列表键盘导航（j/k 翻行、Enter 开详情、x 勾选） ----------
   高亮落点直接存在 DOM（.kb-active）而不是模块变量：整页重绘会重建所有行，
   DOM 上的标记连同旧节点一起消失，天然“翻页即复位”，不需要在每个渲染入口手动清。 */
function listRows() {
    return Array.from(document.querySelectorAll('#bookList .book-item'));
}

/* 当前高亮行下标；DOM 重建后（翻页/筛选）返回 -1，下一次 j/k 重新从行首/行尾开始 */
function kbIndex() {
    return listRows().findIndex(row => row.classList.contains('kb-active'));
}

function setKbCursor(index) {
    const rows = listRows();
    if (rows.length === 0) return;
    const target = Math.max(0, Math.min(rows.length - 1, index));
    rows.forEach((row, i) => row.classList.toggle('kb-active', i === target));
    rows[target].scrollIntoView({ block: 'nearest' });
}

/* 输入框、下拉、按钮上的按键属于控件本身：只认打字区域和弹窗，
   按钮/链接上的 j/k 没有原生行为，仍交给列表导航 */
function isTypingTarget(target) {
    return target instanceof HTMLInputElement
        || target instanceof HTMLTextAreaElement
        || target instanceof HTMLSelectElement
        || (target instanceof HTMLElement && target.isContentEditable);
}

function handleListShortcut(e) {
    // 组合键留给浏览器（Ctrl+F 查找、Cmd+R 刷新等），不做拦截
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    if (isTypingTarget(e.target)) return;
    // 面板打开时只认 Esc/Tab，避免在弹窗里按 j 把背景列表滚走
    if (isConfigPanelOpen() || isAiKeyPanelOpen() || isAiDetailOpen() || isDropdownOpen) return;
    // 「更多」菜单打开时只允许 Esc 关闭；继续 j/k/x 会让菜单和高亮行错位
    if (document.querySelector('.row-more.menu-open')) return;
    if (listRows().length === 0) return;

    const key = e.key.toLowerCase();
    if (key === 'j') {
        e.preventDefault();
        const current = kbIndex();
        setKbCursor(current < 0 ? 0 : current + 1);
        return;
    }
    if (key === 'k') {
        e.preventDefault();
        const current = kbIndex();
        setKbCursor(current < 0 ? listRows().length - 1 : current - 1);
        return;
    }
    if (key === 'x') {
        const current = kbIndex();
        if (current < 0) return;
        e.preventDefault();
        const box = /** @type {HTMLInputElement|null} */ (
            listRows()[current].querySelector('.row-check'));
        if (box) {
            box.checked = !box.checked;
            // 勾选状态归 state.selection 管：派发 change 让已有的委托去同步，不在这里抄一份逻辑
            box.dispatchEvent(new Event('change', { bubbles: true }));
        }
        return;
    }
    if (e.key === 'Enter') {
        // 焦点在按钮/链接上时回车是它自己的激活键，不该再触发列表动作
        if (e.target instanceof Element && e.target.closest('button, a')) return;
        const current = kbIndex();
        if (current < 0) return;
        const detail = listRows()[current].querySelector('[data-action="ai-detail"]');
        if (detail instanceof HTMLElement) {
            e.preventDefault();
            detail.click();
        }
    }
}

/* ---------- 登录态 ---------- */

/* 退出登录：无论接口成功还是失败都回登录页——点了「退出」却停在原地，
   用户会以为没退出去，而实际上服务端会话可能早就过期了 */
async function logout() {
    try {
        await logoutApi();
    } catch (e) {
        // 会话已过期时这里拿到 401，本身就是「已退出」的结果
    }
    location.replace('/login');
}

/* ---------- 下拉开关与勾选 ---------- */

function toggleDropdown() {
    const menu = document.getElementById('dropdownMenu');
    if (!menu) return;
    isDropdownOpen = !isDropdownOpen;
    menu.classList.toggle('show', isDropdownOpen);
    syncToggleExpanded();
}

function closeDropdown() {
    const menu = document.getElementById('dropdownMenu');
    if (menu) {
        menu.classList.remove('show');
        isDropdownOpen = false;
    }
    syncToggleExpanded();
}

/* aria-expanded 供 CSS 翻转箭头与读屏器使用，不参与开关判断 */
function syncToggleExpanded() {
    const toggle = document.querySelector('#dropdownMulti .dropdown-toggle');
    if (toggle) toggle.setAttribute('aria-expanded', String(isDropdownOpen));
}

function onCategoryToggle(code, checked) {
    if (checked) {
        if (!state.selectedCategories.includes(code)) {
            state.selectedCategories.push(code);
        }
    } else {
        state.selectedCategories = state.selectedCategories.filter(c => c !== code);
    }
    updateDropdownLabel();
    updateCheckAllState();
    // 勾选集即导航显示集：两处实时同步，并把偏好留给下次访问
    renderNav();
    saveShownPreference(state.selectedCategories);
    onFilterChange();
}

function toggleAllCategories(checked) {
    state.selectedCategories = checked
        ? getDisplayedCategories().map(c => c.code)
        : [];
    document.querySelectorAll('#dropdownMenu input[type="checkbox"]').forEach(cb => {
        const box = /** @type {HTMLInputElement} */ (cb);
        if (box.id !== 'checkAll') box.checked = checked;
    });
    const allCheckbox = /** @type {HTMLInputElement} */ (document.getElementById('checkAll'));
    if (allCheckbox) {
        allCheckbox.checked = checked;
        allCheckbox.indeterminate = false;
    }
    updateDropdownLabel();
    renderNav();
    saveShownPreference(state.selectedCategories);
    onFilterChange();
}
