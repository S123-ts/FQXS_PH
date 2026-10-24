// @ts-check
/* ============================================================
   js/state.js - 页面状态（取代旧版散落的全局 let）
   请求序号/中止器/写令牌/下拉开合/meta 缓存各自内聚在用自己的模块里，不进这里
   ============================================================ */

export const state = {
    // 当前分类 code，'ALL' 是“全部”视图
    currentCategory: 'ALL',
    // “全部”视图勾选的分类 code 列表（allBooks 现在收 code，逗号分隔）
    selectedCategories: [],
    // 筛选下拉是否已按 meta 初始化过：初始化前默认全选，之后尊重用户勾选，
    // 保存展示分类后只裁掉不再展示的，不强行恢复全选
    filterReady: false,
    // “全部”视图是否切到已拉黑列表，决定行内按钮是“拉黑”还是“恢复”
    blockedView: false,
    // “全部”视图是否只显示已关注的书；与 blockedOnly 取交集
    watchedView: false,
    currentPage: 1,
    // 最近一次响应，翻页/改每页条数时直接重画，不再请求上游
    lastData: null,
    // 上一次加载是否带了筛选：决定空列表该说“没有符合”还是“库是空的”
    filtersActive: false,
    // AI 判定能力与分布快照（GET /api/ai/status）：决定是否自动初筛、朱雀进度上限
    aiStatus: null,
    // 免费引擎选择：'glm-4-flash' / 'qwen2.5-7b'，由下拉框写入；自动初筛也用它
    currentEngine: '',
    // 逐引擎判定结论（bookId → [EngineVerdict...]），渲染行内徽章用，翻页/重载时刷新
    aiVerdicts: {},
    /* 当前页多选的 bookId（只对「全部」视图生效）：批量拉黑/恢复用。
       每次整页渲染都会裁到当前页，翻页或换分类后旧选择自然失效 */
    /** @type {Set<string>} */
    selection: new Set()
};

/* 取样规则：三档固定配方，选「默认」时返回 null，让请求不带 sample 参数，
   由后端取默认档（前 10 章，见 SampleSpec.HEAD_CHAPTERS）。
   章数不再由界面提供——三档的章数在后端写死（10 / 10 / 2×5），
   界面上再放一个输入框只会造成「选了最新 10 章却按 17 章跑」的错位。
   与每页条数同理，这是从控件读出的派生状态，不在 state 里另存一份。 */
export function getSampleSpec() {
    const select = /** @type {HTMLSelectElement} */ (document.getElementById('sampleSelect'));
    const strategy = select ? select.value : '';
    if (!strategy) return null;
    return {strategy, count: 0};
}

/* 「重判」勾选：勾了才带 force=true（会再消耗一次模型额度） */
export function isForceRejudge() {
    const checkbox = /** @type {HTMLInputElement} */ (document.getElementById('aiForceChk'));
    return !!(checkbox && checkbox.checked);
}

/* “自定义”读输入框，其余读下拉框；下限 1，避免除出 0 页。
   每页条数是从控件读出来的派生状态，放这里让渲染与动作共用一份。 */
export function getEffectivePageSize() {
    const select = /** @type {HTMLSelectElement} */ (document.getElementById('pageSizeSelect'));
    if (!select) return 30;
    if (select.value !== 'custom') {
        return parseInt(select.value, 10);
    }
    const customInput = /** @type {HTMLInputElement} */ (document.getElementById('customPageSize'));
    const custom = parseInt(customInput.value, 10);
    return isNaN(custom) || custom < 1 ? 1 : custom;
}

/* ---------- 分类显示偏好（筛选勾选集 ⇄ 导航显示集，localStorage 持久化） ---------- */

const SHOWN_PREF_KEY = 'fqxs.shownCategories';

/* 读回偏好，形状不对一律当没有；是否与当前 displayed 有交集由调用方裁剪 */
export function loadShownPreference() {
    try {
        const raw = localStorage.getItem(SHOWN_PREF_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) return null;
        const codes = parsed.filter(c => typeof c === 'string');
        return codes.length > 0 ? codes : null;
    } catch {
        return null; // 隐私模式等场景 localStorage 可能直接抛错
    }
}

/* 空勾选集不保存：刷新后回落到“全部勾选”，避免把“未选择分类”的临时状态固化 */
export function saveShownPreference(codes) {
    try {
        if (Array.isArray(codes) && codes.length > 0) {
            localStorage.setItem(SHOWN_PREF_KEY, JSON.stringify(codes));
        } else {
            localStorage.removeItem(SHOWN_PREF_KEY);
        }
    } catch {
        // 写不进去就算了，偏好只是增强，不该打断主流程
    }
}
