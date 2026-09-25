/* ============================================================
   js/render_config.js - 「展示分类」设置面板：渲染 + 开合 + 草稿
   面板是视图层自足的单元：画内容、记勾选草稿、管开合与焦点。
   点击/键盘接线在 events.js，保存动作在 data.js（成功后调 closeConfigPanel）
   ============================================================ */

import { getAllGroups, getDisplayedCategories, getCategories } from './meta.js';
import { escapeText, escapeAttr, showToast } from './render.js';

// 面板打开期间的勾选草稿（Set<code>）；点取消/Esc 关闭即丢弃，不落回 state
let draft = new Set();

// 打开面板的触发按钮，关闭时把焦点还给它
let configOpener = null;

/* ---------- 开合与焦点管理 ---------- */

export function isConfigPanelOpen() {
    const mask = document.getElementById('configMask');
    return !!mask && !mask.hidden;
}

export function openConfigPanel(opener) {
    const mask = document.getElementById('configMask');
    if (!mask || !mask.hidden) return;
    if (getCategories().length === 0) {
        // meta 没加载成功时打开面板只会看到“目录为空”，保存也没有意义——直接拦下并提示
        showToast('分类目录还没加载，请先点「🔄 刷新」', 'warn');
        return;
    }
    const firstCheckbox = buildConfigPanel();
    configOpener = opener instanceof Element ? opener : null;
    mask.hidden = false;
    // 焦点直接落到表单区；目录为空时退到关闭按钮，别让焦点留在被遮住的页面里
    const focusTarget = firstCheckbox || mask.querySelector('.modal-close');
    if (focusTarget) focusTarget.focus();
}

export function closeConfigPanel() {
    const mask = document.getElementById('configMask');
    if (!mask || mask.hidden) return;
    mask.hidden = true;
    // 焦点还给触发按钮，键盘/读屏用户不会“掉”到 body 上
    if (configOpener && document.contains(configOpener)) {
        configOpener.focus();
    }
    configOpener = null;
}

/* Tab 焦点圈定：焦点在对话框内循环，不漏到遮罩底下的页面 */
export function trapFocusInConfigPanel(e) {
    const mask = document.getElementById('configMask');
    const dialog = mask && mask.querySelector('.modal');
    if (!dialog) return;
    const focusables = Array.from(dialog.querySelectorAll('button, input, a[href]'))
        .filter(el => !el.disabled);
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

/* ---------- 面板内容 ---------- */

/* 用 meta 重建面板并把草稿重置为当前 displayed 集（checkbox 初始态 = displayed）；
   返回第一个 checkbox 供聚焦，目录为空时返回 null */
export function buildConfigPanel() {
    draft = new Set(getDisplayedCategories().map(c => c.code));
    const body = document.getElementById('configBody');
    if (!body) return null;

    const groups = getAllGroups().filter(g => g.items.length > 0);
    if (groups.length === 0) {
        body.innerHTML = '<p class="config-empty">分类目录为空</p>';
        return null;
    }

    body.innerHTML = groups.map(group => `
        <section class="config-group" data-gender="${escapeAttr(group.gender)}">
            <div class="config-group-head">
                <h3>${escapeText(group.label)}
                    <span class="config-group-count" data-role="group-count"></span>
                </h3>
                <div class="config-group-actions">
                    <button type="button" class="link-btn" data-action="group-all" data-gender="${escapeAttr(group.gender)}">全选</button>
                    <button type="button" class="link-btn" data-action="group-clear" data-gender="${escapeAttr(group.gender)}">清空</button>
                </div>
            </div>
            <div class="config-grid">
                ${group.items.map(c => `
                    <label class="config-item">
                        <input type="checkbox" data-cat-code="${escapeAttr(c.code)}"${draft.has(c.code) ? ' checked' : ''}>
                        <span>${escapeText(c.name)}</span>
                    </label>
                `).join('')}
            </div>
        </section>
    `).join('');
    updateGroupCounts();
    return body.querySelector('input[type="checkbox"]');
}

/* 面板里单个 checkbox 变化：只动草稿和计数，DOM 勾选态已经是事实 */
export function onConfigItemToggle(code, checked) {
    if (!code) return;
    if (checked) {
        draft.add(code);
    } else {
        draft.delete(code);
    }
    updateGroupCounts();
}

/* 全选/清空某个性别组：DOM 勾选态与草稿一起动 */
export function setGroupChecked(gender, checked) {
    document.querySelectorAll('#configBody .config-group').forEach(section => {
        if (Number(section.dataset.gender) !== gender) return;
        section.querySelectorAll('input[type="checkbox"]').forEach(input => {
            input.checked = checked;
            if (checked) {
                draft.add(input.dataset.catCode);
            } else {
                draft.delete(input.dataset.catCode);
            }
        });
    });
    updateGroupCounts();
}

/* 草稿按 meta 目录顺序输出，POST 出去的数组顺序稳定可预期 */
export function getConfigDraftCodes() {
    return getCategories().map(c => c.code).filter(code => draft.has(code));
}

function updateGroupCounts() {
    document.querySelectorAll('#configBody .config-group').forEach(section => {
        const counter = section.querySelector('[data-role="group-count"]');
        if (!counter) return;
        const inputs = section.querySelectorAll('input[type="checkbox"]');
        let checked = 0;
        inputs.forEach(input => {
            if (input.checked) checked += 1;
        });
        counter.textContent = `${checked}/${inputs.length}`;
    });
}

/* 保存请求在飞行中：按钮禁用防重复提交 */
export function setConfigSaveBusy(busy) {
    const btn = document.getElementById('configSaveBtn');
    if (!btn) return;
    btn.disabled = busy;
    btn.textContent = busy ? '保存中...' : '保存';
}
