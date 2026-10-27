// @ts-check
/* ============================================================
   js/ai_config.js - 「AI 引擎密钥」设置面板
   职责：画三个引擎的密钥表单、开合与焦点、保存/清除。
   密钥只经 POST /api/ai/keys 交给后端写进本机 data/ai-keys.properties，
   页面不缓存明文、不回显明文（服务端只回掩码）。
   ============================================================ */

import {
    fetchAiKeysApi, saveAiKeyApi, saveAiModelApi, fetchAiQuotaApi, fetchAiUsageApi,
    testAiEngineApi, saveAiEngineApi, deleteAiEngineApi, discoverAiModelsApi,
    saveAiEngineEnabledApi
} from './api.js';
import { escapeText, escapeAttr, showToast } from './render.js';
import { loadAiStatus } from './ai_actions.js';

/* 三个内置引擎的展示信息与官方取 Key 入口。文案给出注册入口与 Key 形态，
   减少查阅文档的步骤；更长的图文步骤见 README「AI 文判定」。
   朱雀通道已移除，判定统一走 OpenAI 兼容的三家。 */
const ENGINES = [
    {
        id: 'glm-4-flash',
        name: 'GLM-4-Flash · 智谱',
        tip: '永久免费、30 并发。注册后进「API 密钥」页新建，Key 形如 xxxx.yyyy（带一个点）。',
        url: 'https://open.bigmodel.cn/usercenter/apikeys',
        // 没配 Key 时直接给首页：未登录用户进 /usercenter/apikeys 会被弹去登录，
        // 不如从首页进（注册/开通入口都在那儿）
        homeUrl: 'https://open.bigmodel.cn/',
        linkText: '获取 GLM Key ↗'
    },
    {
        id: 'qianfan-ernie-speed',
        name: '百度千帆 ERNIE',
        tip: '需先在千帆控制台开通在线服务；无零价型号，赠送额度型按量计费。',
        url: 'https://console.bce.baidu.com/qianfan/ais/console/apiKey',
        linkText: '获取千帆 Key ↗'
    },
    {
        id: 'qwen2.5-7b',
        name: 'Qwen2.5-7B · 硅基流动',
        tip: '免费档。注册后进「API 密钥」新建，Key 形如 sk-xxxxxxxx。',
        url: 'https://cloud.siliconflow.cn/me/models',
        linkText: 'Qwen 模型 ↗'
    }
];

// 打开面板的触发按钮，关闭时把焦点还给它
let openerEl = null;

/* 可选模型：第一项是默认档（免费且最稳），付费/限流型号标注清楚。
   引擎标识（通道）不随模型变——明细表与待判集都按通道统计，所以换模型
   不会让已判过的书重新排队。 */
const MODEL_OPTIONS = {
    'glm-4-flash': [
        ['glm-4-flash', 'GLM-4-Flash（免费 · 最稳）'],
        ['glm-4-flash-250414', 'GLM-4-Flash-250414（免费 · 官方完整名）'],
        ['glm-4.7-flash', 'GLM-4.7-Flash（免费 · 200K · 高峰易限流）'],
        ['glm-4.5-flash', 'GLM-4.5-Flash（免费 · 128K）'],
        ['glm-4.7', 'GLM-4.7（付费 · 判别更细）'],
        ['glm-5.3-flash', 'GLM-5.3-Flash（付费 · 1M 上下文）']
    ],
    'qwen2.5-7b': [
        ['Qwen/Qwen2.5-7B-Instruct', 'Qwen2.5-7B（免费档）'],
        ['Qwen/Qwen2.5-14B-Instruct', 'Qwen2.5-14B（额度更高）'],
        ['Qwen/Qwen3-8B', 'Qwen3-8B（较新）']
    ]
};

export function isAiKeyPanelOpen() {
    const mask = document.getElementById('aiKeyMask');
    return !!mask && !mask.hidden;
}

export async function openAiKeyPanel(opener) {
    const mask = document.getElementById('aiKeyMask');
    if (!mask || !mask.hidden) return;
    openerEl = opener instanceof Element ? opener : null;
    const body = document.getElementById('aiKeyBody');
    body.innerHTML = '<p class="key-loading">读取密钥状态...</p>';
    mask.hidden = false;
    try {
        const keys = await loadKeys();
        renderPanel(body, keys);
        refreshQuota(); // 额度要出网查，异步补，不阻塞面板打开
        refreshUsage(); // 用量读本地库，同样异步补
        const firstInput = body.querySelector('.key-input');
        /** @type {HTMLElement} */ (firstInput || mask.querySelector('.modal-close'))?.focus();
    } catch (err) {
        body.innerHTML = `<p class="key-loading">读取失败：${escapeText(err.message || String(err))}</p>`;
    }
}

export function closeAiKeyPanel() {
    const mask = document.getElementById('aiKeyMask');
    if (!mask || mask.hidden) return;
    mask.hidden = true;
    if (openerEl && document.contains(openerEl)) {
        openerEl.focus();
    }
    openerEl = null;
}

/* Tab 焦点圈定在对话框内（与「展示分类」面板同一套做法） */
export function trapFocusInAiKeyPanel(e) {
    const mask = document.getElementById('aiKeyMask');
    const dialog = mask && mask.querySelector('.modal');
    if (!dialog) return;
    const focusables = /** @type {HTMLElement[]} */ (
        Array.from(dialog.querySelectorAll('button, input, a[href]')))
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

/* ---------- 保存 / 清除 ---------- */

let saveInFlight = false;

/* 只提交「填了内容」的输入框：留空表示不修改该引擎，避免一次保存把其它引擎清掉 */
export async function saveAiKeys() {
    if (saveInFlight) return;
    const body = document.getElementById('aiKeyBody');
    if (!body) return;
    const pending = /** @type {HTMLInputElement[]} */ ([...body.querySelectorAll('.key-input')])
        .map(input => ({engine: input.dataset.engine, key: input.value.trim()}))
        .filter(item => item.key.length > 0);
    const clearing = /** @type {HTMLElement[]} */ ([...body.querySelectorAll('.key-row.clearing')])
        .map(row => row.dataset.engine);

    if (pending.length === 0 && clearing.length === 0) {
        // 没有改动 = 这次保存无事可做：直接关掉面板当作"完成"。
        // 以前弹「没有需要保存的改动」，用户会以为没保存成功而反复点保存，
        // 结果是连点五次叠五条 toast、面板还开着——真正的空状态不需要提示。
        closeAiKeyPanel();
        return;
    }

    saveInFlight = true;
    setSaveBusy(true);
    try {
        // 逐项收集成败：第 2 个失败时第 1 个已经落盘，不能笼统说「保存失败」
        let saved = 0;
        const failures = [];
        for (const item of pending) {
            try {
                await saveAiKeyApi(item.engine, item.key);
                saved++;
            } catch (err) {
                failures.push(`${item.engine}：${err.message || err}`);
            }
        }
        for (const engine of clearing) {
            try {
                await saveAiKeyApi(engine, '');
                saved++;
            } catch (err) {
                failures.push(`${engine}：${err.message || err}`);
            }
        }
        if (failures.length === 0) {
            const parts = [];
            if (pending.length) parts.push(`保存 ${pending.length} 个`);
            if (clearing.length) parts.push(`清除 ${clearing.length} 个`);
            showToast(`密钥已${parts.join('、')}，立即生效`, 'success', 5000);

            // 掩码与引擎下拉都要跟着变：先重画面板，再刷新 status（下拉来源）
            renderPanel(body, await loadKeys());
            refreshQuota(); // Key 变了余额也跟着变
            await loadAiStatus();
        } else {
            // 失败时不重画面板：输入还在框里，用户可以直接补存
            showToast(`已保存 ${saved} 个，失败 ${failures.length} 个（${failures.join('；')}）`,
                'error', 9000);
            refreshQuota();
        }
    } finally {
        saveInFlight = false;
        setSaveBusy(false);
    }
}

/* 点「清除」只是打标记，真正提交在「保存」里——误点可以再点一次取消 */
export function toggleClearKey(engine) {
    const row = document.querySelector(`#aiKeyBody .key-row[data-engine="${CSS.escape(engine)}"]`);
    if (!row) return;
    const clearing = row.classList.toggle('clearing');
    const btn = row.querySelector('[data-action="ai-key-clear"]');
    if (btn) btn.textContent = clearing ? '取消清除' : '清除';
    row.querySelector('.key-status')?.classList.toggle('clearing', clearing);
    const hint = /** @type {HTMLElement} */ (row.querySelector('.key-clear-hint'));
    if (hint) hint.hidden = !clearing;
}

/* ---------- 渲染 ---------- */

async function loadKeys() {
    const body = await fetchAiKeysApi();
    return (body && body.data) || {};
}

function renderPanel(host, keys) {
    // 引擎列表以服务端注册表为准（内置 + 用户自带），本地 ENGINES 只提供取 Key 入口文案
    const entries = Object.entries(keys || {});
    /* 手机上面板可视高度只有 86vh：所有引擎默认收起，先看到三个标题行与状态；
       桌面仍保持「已配置展开、未配置收起」的原行为 */
    const compact = window.matchMedia('(max-width: 640px)').matches;
    host.innerHTML = entries.map(([id, state]) => {
        const meta = /** @type {{name?: string, tip?: string, url?: string, homeUrl?: string, linkText?: string}} */ (
            ENGINES.find(engine => engine.id === id) || {});
        const configured = !!state.configured;
        const collapsed = !configured || compact;
        const statusClass = configured ? 'key-status ok' : 'key-status';
        const statusText = configured ? `已配置 ${escapeText(state.masked || '')}` : '未配置';
        const name = state.name || meta.name || id;
        const tip = state.remark || meta.tip || '';
        const link = meta.url
            ? `<a class="key-link" href="${escapeAttr(configured && meta.homeUrl ? meta.homeUrl : meta.url)}"
                   target="_blank" rel="noopener noreferrer">${escapeText(meta.linkText)}</a>`
            : '';
        // 未配置的引擎默认收起：面板一打开就先看到"已经配好的"，需要配置的再点开
        return `
            <div class="key-row${collapsed ? ' collapsed' : ''}" data-engine="${escapeAttr(id)}"
                 data-configured="${configured ? '1' : '0'}" data-enabled="${state.enabled ? '1' : '0'}">
                <div class="key-head">
                    <span class="key-name">${escapeText(name)}${state.builtin ? '' : ' · 自定义'}</span>
                    <span class="${statusClass}">${statusText}</span>
                    <label class="key-enable" title="关掉后不参与判定，Key 保留">
                        <input type="checkbox" class="key-enable-check" data-action="ai-engine-toggle"
                               data-engine="${escapeAttr(id)}"${state.enabled ? ' checked' : ''}>
                        <span>启用</span>
                    </label>
                    ${(!configured || compact) ? `<button type="button" class="key-expand" data-action="ai-key-expand"
                            data-engine="${escapeAttr(id)}">${collapsed ? (configured ? '编辑' : '配置') : '收起'}</button>` : ''}
                    <button type="button" class="key-expand" data-action="ai-engine-test"
                            data-engine="${escapeAttr(id)}">测试</button>
                    ${state.builtin ? '' : `<button type="button" class="key-expand" data-action="ai-engine-delete"
                            data-engine="${escapeAttr(id)}">删除</button>`}
                    ${link}
                </div>
                <div class="key-body">
                    <p class="key-tip">${escapeText(tip)}</p>
                    <p class="key-tip key-tip-endpoint">端点：${escapeText(state.baseUrl || '')}</p>
                    <div class="key-input-row">
                        <input type="password" class="input key-input" data-engine="${escapeAttr(id)}"
                               placeholder="${configured ? '已配置，留空不修改；粘贴新 Key 可覆盖' : '粘贴 Key'}"
                               autocomplete="off" spellcheck="false" aria-label="${escapeAttr(name)} 的 API Key">
                        <button type="button" class="link-btn" data-action="ai-key-clear"
                                data-engine="${escapeAttr(id)}">清除</button>
                    </div>
                    ${renderModelRow(id, state.model, state.models, state.modelPool, state.modelOptions)}
                    <div class="key-quota" data-engine="${escapeAttr(id)}">
                        <span class="quota-muted">额度查询中…</span>
                    </div>
                    <p class="key-clear-hint" hidden>点「保存」后清除该引擎的密钥</p>
                </div>
            </div>
        `;
    }).join('') + `
        <p class="key-usage" id="aiUsageLine"></p>
        <details class="key-add">
            <summary>＋ 添加自定义引擎（任意 OpenAI 兼容接口）</summary>
            <div class="key-add-body">
                <input class="input" id="newEngineName" placeholder="名称，如 我的 DeepSeek" autocomplete="off">
                <input class="input" id="newEngineUrl" placeholder="接口地址，如 https://api.deepseek.com/v1" autocomplete="off">
                <input class="input" id="newEngineModel" placeholder="模型名，如 deepseek-chat" autocomplete="off">
                <button type="button" class="btn btn-primary" data-action="ai-engine-add">添加</button>
            </div>
            <p class="key-add-hint">添加后在列表里填 Key 即可启用。Key 只会发往你填写的这个地址。</p>
        </details>
        <p class="key-note">
            密钥只写进本机 <code>data/ai-keys.properties</code>，该目录已在 <code>.gitignore</code> 里，
            <strong>不会提交到 GitHub</strong>；页面只显示掩码，日志不打印明文。
            保存后立即生效，无需重启。环境变量 <code>GLM_API_KEY</code> 等优先于本面板的值。
        </p>
    `;
}

/* 添加一个自定义 OpenAI 兼容引擎：Key 稍后在列表里填，避免一次提交两个写接口 */
export async function addEngine() {
    const name = /** @type {HTMLInputElement} */ (document.getElementById('newEngineName'))?.value.trim();
    const baseUrl = /** @type {HTMLInputElement} */ (document.getElementById('newEngineUrl'))?.value.trim();
    const model = /** @type {HTMLInputElement} */ (document.getElementById('newEngineModel'))?.value.trim();
    if (!baseUrl || !model) {
        showToast('接口地址和模型名必填', 'warn', 5000);
        return;
    }
    try {
        const body = await saveAiEngineApi({name: name || model, baseUrl, model, enabled: true});
        showToast((body && body.message) || '已添加', 'success', 5000);
        const host = document.getElementById('aiKeyBody');
        if (host) renderPanel(host, (body && body.data) || {});
        await loadAiStatus();
    } catch (err) {
        showToast('添加失败: ' + (err.message || err), 'error', 7000);
    }
}

/* 从端点拉取型号清单：只扩充候选，已勾选的池不变 */
export async function discoverModels(engine, btn) {
    if (!engine) return;
    const original = btn ? btn.textContent : '';
    if (btn) {
        btn.disabled = true;
        btn.textContent = '拉取中…';
    }
    try {
        const body = await discoverAiModelsApi(engine);
        showToast((body && body.message) || '型号清单已更新', 'success', 6000);
        const host = document.getElementById('aiKeyBody');
        if (host) renderPanel(host, (body && body.data) || {});
    } catch (err) {
        showToast('拉取型号失败: ' + (err.message || err), 'error', 7000);
        if (btn) {
            btn.disabled = false;
            btn.textContent = original;
        }
    }
}

/* 测试一个引擎：真实发一条样例，结果显示在按钮上 */
export async function testEngine(engine, btn) {
    if (!engine) return;
    const original = btn ? btn.textContent : '';
    if (btn) {
        btn.disabled = true;
        btn.textContent = '测试中…';
    }
    try {
        const body = await testAiEngineApi(engine);
        const result = (body && body.data) || {};
        showToast((body && body.message) || (result.ok ? '连接成功' : '测试失败'),
            result.ok ? 'success' : 'error', 7000);
        if (btn) btn.textContent = result.ok ? `✓ ${result.responseMs} ms` : '测试';
    } catch (err) {
        showToast('测试失败: ' + (err.message || err), 'error', 7000);
        if (btn) btn.textContent = '测试';
    } finally {
        if (btn) btn.disabled = false;
        if (btn && btn.textContent === '测试中…') btn.textContent = original;
    }
}

/* 删除确认态：与行内「拉黑」同一套交互（第一次点变成确认，3 秒不点自动复位），
   不用 window.confirm：原生弹窗样式不可控，与应用内的交互语言也不一致 */
let deleteConfirmBtn = null;
let deleteConfirmTimer = 0;

function resetDeleteConfirm() {
    if (deleteConfirmBtn) {
        deleteConfirmBtn.classList.remove('confirm');
        deleteConfirmBtn.removeAttribute('data-confirm');
        deleteConfirmBtn.textContent = '删除';
    }
    clearTimeout(deleteConfirmTimer);
    deleteConfirmBtn = null;
}

/* 删除自定义引擎：内置引擎后端会拒绝 */
export async function removeEngine(engine, btn) {
    if (!engine) return;
    if (!btn) return;
    if (btn.dataset.confirm !== '1') {
        resetDeleteConfirm();
        deleteConfirmBtn = btn;
        btn.classList.add('confirm');
        btn.dataset.confirm = '1';
        btn.textContent = '确认删除?';
        deleteConfirmTimer = window.setTimeout(resetDeleteConfirm, 3000);
        return;
    }
    resetDeleteConfirm();
    try {
        const body = await deleteAiEngineApi(engine);
        showToast((body && body.message) || '已删除', 'success', 5000);
        const host = document.getElementById('aiKeyBody');
        if (host) renderPanel(host, await loadKeys());
        await loadAiStatus();
    } catch (err) {
        showToast('删除失败: ' + (err.message || err), 'error', 6000);
    }
}

/* 展开/收起一个未配置的引擎：只切 class，DOM 不重建，已输入的 Key 不会丢 */
export function toggleKeyRow(engine, btn) {
    if (!engine) return;
    const row = document.querySelector(`#aiKeyBody .key-row[data-engine="${CSS.escape(engine)}"]`);
    if (!row) return;
    const collapsed = row.classList.toggle('collapsed');
    if (btn) {
        btn.textContent = collapsed ? (row.getAttribute('data-configured') === '1' ? '编辑' : '配置') : '收起';
    }
    if (!collapsed) {
        /** @type {HTMLElement} */ (row.querySelector('.key-input'))?.focus();
    }
}

function setSaveBusy(busy) {
    const btn = /** @type {HTMLButtonElement} */ (document.getElementById('aiKeySaveBtn'));
    if (!btn) return;
    btn.disabled = busy;
    btn.textContent = busy ? '保存中...' : '保存';
}

/* 用量行：近 7 天调用次数与 token（不估成本）；失败不打断面板 */
async function refreshUsage() {
    const line = document.getElementById('aiUsageLine');
    if (!line) return;
    try {
        const body = await fetchAiUsageApi(7);
        const data = (body && body.data) || {};
        const totals = data.totals || {};
        const calls = Number(totals.calls || 0);
        if (calls === 0) {
            line.innerHTML = '<span class="quota-muted">近 7 天暂无 AI 调用</span>';
            return;
        }
        const engines = (data.byEngine || [])
            .map(row => `${escapeText(row.engine)} ${Number(row.calls || 0)} 次`)
            .join(' · ');
        line.innerHTML = `<span class="quota-muted">近 7 天 ${calls} 次调用`
            + ` · 输入 ${Number(totals.promptTokens || 0)} / 输出 ${Number(totals.completionTokens || 0)} tokens</span>`
            + (engines ? `<span class="quota-sub">${engines}</span>` : '');
    } catch (err) {
        console.warn('用量读取失败:', err);
        line.innerHTML = '<span class="quota-warn">用量读取失败</span>';
    }
}

/* 额度查询：只有硅基流动开放了余额接口，智谱回的是规则说明。
   失败只影响这一行，不打断面板 */
async function refreshQuota() {
    const host = document.getElementById('aiKeyBody');
    if (!host) return;
    try {
        const body = await fetchAiQuotaApi();
        const quotas = (body && body.data) || {};
        host.querySelectorAll('.key-quota').forEach(el => {
            el.innerHTML = renderQuota(quotas[/** @type {HTMLElement} */ (el).dataset.engine]);
        });
    } catch (err) {
        console.warn('额度查询失败:', err);
        host.querySelectorAll('.key-quota').forEach(el => {
            el.innerHTML = `<span class="quota-warn">额度查询失败：${escapeText(err.message || String(err))}</span>`;
        });
    }
}

/* 额度行的几种形态：真实余额 / 规则说明 / 未配置 / 出错。
   有效期一律不臆造——三家平台的接口都不返回到期时间 */
function renderQuota(quota) {
    if (!quota) return '<span class="quota-muted">无额度信息</span>';
    if (!quota.configured) return '<span class="quota-muted">未配置密钥</span>';
    if (quota.error) {
        return `<span class="quota-warn">${escapeText(quota.error)}</span>`
            + consoleLink(quota.consoleUrl, '控制台');
    }
    if (quota.supported && quota.totalBalance !== undefined && quota.totalBalance !== null) {
        const total = Number(quota.totalBalance).toFixed(2);
        const gift = Number(quota.giftBalance || 0).toFixed(2);
        const charge = Number(quota.chargeBalance || 0).toFixed(2);
        return `<span class="quota-ok">余额 ¥${total}</span>`
            + `<span class="quota-sub">赠送 ¥${gift} · 充值 ¥${charge}</span>`
            + `<span class="quota-sub">${escapeText(quota.note || '')}</span>`
            + consoleLink(quota.consoleUrl, '控制台');
    }
    // 平台没有查询接口：给出规则说明与出处，不编造剩余量。
    // free === false 说明当前选的是付费型号，必须显式警示，避免误认为仍在免费运行
    const tier = quota.free === true
        ? '<span class="quota-free">免费档</span>'
        : (quota.free === false ? '<span class="quota-paid">付费模型</span>' : '');
    return tier
        + `<span class="quota-muted">${escapeText(quota.note || '')}</span>`
        + (quota.reason ? `<span class="quota-sub">（${escapeText(quota.reason)}）</span>` : '')
        + consoleLink(quota.consoleUrl, '查看');
}

function consoleLink(url, text) {
    if (!url) return '';
    return ` <a class="quota-link" href="${escapeAttr(url)}" target="_blank"
              rel="noopener noreferrer">${escapeText(text)} ↗</a>`;
}
function renderModelRow(engineId, currentModel, modelList, modelPool, modelOptions) {
    // 型号清单优先用服务端下发的；老接口没有 models 字段时回退到本地常量
    const fromApi = Array.isArray(modelList) && modelList.length > 0;
    const options = fromApi
        ? [...modelList]
        : (MODEL_OPTIONS[engineId] || []).map(([id]) => id);
    if (options.length === 0) return '';
    // 当前型号不在清单里时补进去，避免用户看不到自己正在用的型号
    if (currentModel && !options.includes(currentModel)) {
        options.unshift(currentModel);
    }
    const freeById = new Set((Array.isArray(modelOptions) ? modelOptions : [])
        .filter(option => option && option.free)
        .map(option => option.id));
    const selected = new Set(
        Array.isArray(modelPool) && modelPool.length ? modelPool : (currentModel ? [currentModel] : []));
    // 端点拉取可能带回上百个型号（含 embedding 等非对话模型）：
    // 默认只铺一屏，已勾选的必显，其余点「展开全部」再看，避免面板被撑到几千像素
    const visibleLimit = 24;
    const keep = new Set(options.slice(0, visibleLimit));
    selected.forEach(id => keep.add(id));
    const hiddenCount = options.filter(id => !keep.has(id)).length;
    const chips = options.map(id => `
        <label class="key-model-chip"${keep.has(id) ? '' : ' hidden'}>
            <input type="checkbox" class="key-model-check" data-engine="${escapeAttr(engineId)}"
                   value="${escapeAttr(id)}"${selected.has(id) ? ' checked' : ''}>
            ${escapeText(id)}${freeById.has(id) ? '<span class="model-free">免费</span>' : ''}
        </label>`).join('');
    return `
        <div class="key-model-row">
            <span class="key-model-label">模型</span>
            <div class="key-model-chips">${chips}</div>
            <span class="key-model-hint">勾选多个时每次判定随机取一个；全不勾则固定用当前型号</span>
            <button type="button" class="link-btn" data-action="ai-engine-discover"
                    data-engine="${escapeAttr(engineId)}">拉取端点型号</button>
            ${hiddenCount > 0 ? `<button type="button" class="link-btn" data-action="ai-model-expand">展开全部 ${options.length} 个</button>` : ''}
        </div>
    `;
}

/* 展开被折叠的型号芯片（只影响当前面板，不改数据） */
/* 启用开关：只改 enabled，不动 Key 与型号；停用后引擎仍保留 Key，
   只是不再进判定下拉与随机池（后端 availableEngines 按 enabled 过滤） */
export async function toggleEngine(engine, checkbox) {
    if (!engine) return;
    const next = !!(checkbox && checkbox.checked);
    if (checkbox) checkbox.disabled = true;
    try {
        const body = await saveAiEngineEnabledApi(engine, next);
        const row = /** @type {HTMLElement | null} */ (
            document.querySelector(`#aiKeyBody .key-row[data-engine="${CSS.escape(engine)}"]`));
        if (row) row.dataset.enabled = next ? '1' : '0';
        showToast((body && body.message) || (next ? '已启用' : '已停用'), 'success', 3000);
        await loadAiStatus();
    } catch (err) {
        if (checkbox) checkbox.checked = !next;
        console.error('切换启用状态失败:', err);
        showToast('切换启用状态失败: ' + (err.message || err), 'error', 6000);
    } finally {
        if (checkbox) checkbox.disabled = false;
    }
}

export function expandModelList(btn) {
    const row = btn && btn.closest('.key-model-row');
    if (!row) return;
    row.querySelectorAll('.key-model-chip[hidden]').forEach(el => {
        /** @type {HTMLElement} */ (el).hidden = false;
    });
    btn.remove();
}

/* 型号勾选变化即保存：多选时后端存型号池，判定时随机取一个；
   全不勾也合法（后端清空池后回退主型号），所以这里不再拦「至少保留一个」 */
export async function onModelPoolChange(engine, checkbox) {
    const host = document.getElementById('aiKeyBody');
    if (!engine || !host) return;
    const checks = /** @type {HTMLInputElement[]} */ (
        [...host.querySelectorAll(`.key-model-check[data-engine="${CSS.escape(engine)}"]`)]);
    const models = checks.filter(check => check.checked).map(check => check.value);
    try {
        const body = await saveAiModelApi(engine, null, models);
        showToast((body && body.message) || '型号已更新', 'success', 4000);
        refreshQuota();
    } catch (err) {
        console.error('保存型号失败:', err);
        // 失败只回滚勾选，保留用户已输入的 Key
        if (checkbox) checkbox.checked = !checkbox.checked;
        showToast('保存型号失败: ' + (err.message || err), 'error', 6000);
    }
}
