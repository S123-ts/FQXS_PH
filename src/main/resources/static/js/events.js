/* ============================================================
   js/events.js - 事件绑定
   原则：除 document 级监听外不在动态节点上挂监听器，
   动态元素一律靠 data-* + 事件委托识别
   ============================================================ */

import { state } from './state.js';
import { getDisplayedCategories } from './meta.js';
import { updateCheckAllState, updateDropdownLabel } from './render.js';
import {
    loadData, fetchAllData, goToPage, onFilterChange, onPageSizeChange,
    setBlocked, switchCategory, saveRankType, saveDisplayCategories
} from './data.js';
import {
    openConfigPanel, closeConfigPanel, isConfigPanelOpen,
    trapFocusInConfigPanel, getConfigDraftCodes, onConfigItemToggle,
    setGroupChecked
} from './render_config.js';

// 下拉开合状态只归本模块管
let isDropdownOpen = false;

export function bindEvents() {
    /* ---------- click 全量委托到 document：
       导航/翻页/拉黑/刷新/一键获取/下拉开关/设置面板 ---------- */
    document.addEventListener('click', (e) => {
        if (!(e.target instanceof Element)) return;

        const toggle = e.target.closest('.dropdown-toggle');
        if (toggle) {
            toggleDropdown();
            return;
        }

        const navBtn = e.target.closest('.nav-btn[data-code]');
        if (navBtn) {
            switchCategory(navBtn.dataset.code);
            return;
        }

        const pagerBtn = e.target.closest('.pager-btn[data-page]');
        if (pagerBtn) {
            goToPage(parseInt(pagerBtn.dataset.page, 10));
            return;
        }

        const blockBtn = e.target.closest('.block-btn');
        if (blockBtn) {
            // data-action 是 block / unblock，在这里读成布尔
            setBlocked(blockBtn.dataset.bookId, blockBtn.dataset.action !== 'unblock', blockBtn);
            return;
        }

        // 点在遮罩空白处（而非对话框内部）同样视为关闭
        if (e.target.id === 'configMask') {
            closeConfigPanel();
            return;
        }

        const actionBtn = e.target.closest('[data-action]');
        if (actionBtn) {
            switch (actionBtn.dataset.action) {
                case 'refresh':
                    loadData();
                    break;
                case 'fetch-all':
                    fetchAllData();
                    break;
                case 'open-config':
                    openConfigPanel(actionBtn);
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
            }
        }
    });

    /* 点击下拉外部就收起 —— 独立的 document 级监听 */
    document.addEventListener('click', (e) => {
        const container = document.getElementById('dropdownMulti');
        if (container && !container.contains(e.target)) {
            closeDropdown();
        }
    });

    /* ---------- 键盘：Esc 关设置面板/下拉，面板打开期间 Tab 圈定焦点 ---------- */
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
            if (isConfigPanelOpen()) {
                e.preventDefault();
                closeConfigPanel();
            } else if (isDropdownOpen) {
                // 下拉同样支持 Esc 收起，键盘用户不用摸鼠标
                closeDropdown();
            }
        } else if (e.key === 'Tab' && isConfigPanelOpen()) {
            trapFocusInConfigPanel(e);
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

    // 榜单类型分段单选：radio 的 change 冒泡到静态容器 #rankTypeGroup
    document.getElementById('rankTypeGroup').addEventListener('change', (e) => {
        const input = e.target;
        if (input instanceof HTMLInputElement && input.name === 'rankType') {
            saveRankType(input.value);
        }
    });

    document.getElementById('filterWord').addEventListener('change', onFilterChange);
    document.getElementById('filterStatus').addEventListener('change', onFilterChange);
    document.getElementById('filterBlocked').addEventListener('change', onFilterChange);
    document.getElementById('pageSizeSelect').addEventListener('change', onPageSizeChange);

    const customPageSize = document.getElementById('customPageSize');
    if (customPageSize) {
        // 兜底摆一次显隐：不管 HTML 初始有没有带 hidden，都按 select 当前值对齐
        customPageSize.hidden = document.getElementById('pageSizeSelect').value !== 'custom';
        // 数字框每敲一个字符就重切本地分页，这是刻意行为，不要改成 change
        customPageSize.addEventListener('input', onPageSizeChange);
    }

    /* ---------- 捕获阶段接管 <img> 加载失败 ----------
       error 事件不冒泡，必须用捕获才能拦到；替代旧版内联 onerror。 */
    document.addEventListener('error', (e) => {
        // 只处理图片，别误伤 script/css 等其他资源的失败
        if (e.target && e.target.tagName === 'IMG') {
            e.target.remove(); // 露出底下的 📖 占位
        }
    }, true);
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
    onFilterChange();
}

function toggleAllCategories(checked) {
    state.selectedCategories = checked
        ? getDisplayedCategories().map(c => c.code)
        : [];
    document.querySelectorAll('#dropdownMenu input[type="checkbox"]').forEach(cb => {
        if (cb.id !== 'checkAll') cb.checked = checked;
    });
    const allCheckbox = document.getElementById('checkAll');
    if (allCheckbox) {
        allCheckbox.checked = checked;
        allCheckbox.indeterminate = false;
    }
    updateDropdownLabel();
    onFilterChange();
}
