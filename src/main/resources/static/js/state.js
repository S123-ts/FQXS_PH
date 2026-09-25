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
    currentPage: 1,
    // 最近一次响应，翻页/改每页条数时直接重画，不再请求上游
    lastData: null,
    // 上一次加载是否带了筛选：决定空列表该说“没有符合”还是“库是空的”
    filtersActive: false
};

/* “自定义”读输入框，其余读下拉框；下限 1，避免除出 0 页。
   每页条数是从控件读出来的派生状态，放这里让渲染与动作共用一份。 */
export function getEffectivePageSize() {
    const select = document.getElementById('pageSizeSelect');
    if (!select) return 30;
    if (select.value !== 'custom') {
        return parseInt(select.value, 10);
    }
    const custom = parseInt(document.getElementById('customPageSize').value, 10);
    return isNaN(custom) || custom < 1 ? 1 : custom;
}
