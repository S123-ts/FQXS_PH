/* ============================================================
   js/meta.js - 榜单元信息（榜单类型 + 分类目录）缓存与查询
   分类目录现在是动态的（页面引导走 GET /api/rank/meta），不再有
   内置兜底清单；meta 未就绪前，所有依赖分类的渲染与请求都不该发生
   （入口守卫见 data.js 的 bootstrap / loadData）
   ============================================================ */

let meta = null;

/* 用后端返回的全量视图整体替换本地缓存；
   POST /api/rank/config 保存成功后的回包与 meta 同形，同样喂进来 */
export function setMeta(data) {
    const d = data || {};
    meta = {
        rankTypes: Array.isArray(d.rankTypes) ? d.rankTypes : [],
        activeRankType: d.activeRankType || '',
        categories: Array.isArray(d.categories) ? d.categories : []
    };
}

export function isMetaReady() {
    return meta !== null;
}

export function getRankTypes() {
    return meta ? meta.rankTypes : [];
}

export function getActiveRankType() {
    return meta ? meta.activeRankType : '';
}

export function getCategories() {
    return meta ? meta.categories : [];
}

/* 只有 displayed 的分类才进导航、筛选下拉与一键抓取的范围 */
export function getDisplayedCategories() {
    return getCategories().filter(c => c.displayed);
}

export function findCategory(code) {
    return getCategories().find(c => c.code === code) || null;
}

export function findRankType(code) {
    return getRankTypes().find(r => r.code === code) || null;
}

/* 'ALL' 是页面自己的「全部」视图，不在后端目录里，这里补上名字 */
export function categoryDisplayName(code) {
    if (code === 'ALL') return '全部';
    const cat = findCategory(code);
    return cat ? cat.name : '';
}

/* 按性别分组；约定 gender=1 男频、gender=0 女频，用 Number() 容忍后端
   万一给成字符串。未知值落进女频组而不是凭空消失。
   includeHidden 为真返回全量目录（设置面板用），否则只返回 displayed（导航用） */
function groupByGender(includeHidden) {
    const list = includeHidden ? getCategories() : getDisplayedCategories();
    return [
        { gender: 1, label: '男频', items: list.filter(c => Number(c.gender) === 1) },
        { gender: 0, label: '女频', items: list.filter(c => Number(c.gender) !== 1) }
    ];
}

/* 导航用：只含展示中的分类，男女频各一组 */
export function getDisplayedGroups() {
    return groupByGender(false);
}

/* 设置面板用：全量目录，checkbox 初始态取各项的 displayed */
export function getAllGroups() {
    return groupByGender(true);
}
