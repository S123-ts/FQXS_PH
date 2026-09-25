/* ============================================================
   js/data.js - 数据加载与页面动作（控制器层）
   加载竞态的两个标记与加载逻辑同生共死，收在这里不进 state
   ============================================================ */

import { API_BASE, WHOLE_LIST_SIZE } from './config.js';
import {
    fetchRankList, postJson, setBlockedApi, fetchAllDataApi,
    fetchMetaApi, saveConfigApi
} from './api.js';
import { state } from './state.js';
import {
    isMetaReady, setMeta, getDisplayedCategories, findCategory,
    findRankType, getActiveRankType, categoryDisplayName
} from './meta.js';
import {
    renderBooks, renderEmptyState, renderLoading, renderNav,
    populateFilterCategories, renderRankTypeControls,
    setRankTypeControlsDisabled, updateStatsMeta, showToast
} from './render.js';
import { closeConfigPanel, setConfigSaveBusy } from './render_config.js';

// 请求序号与中止器：慢响应后到时不许盖住新分类的数据
let loadSeq = 0;
let loadController = null;

/* ---------- 页面引导 ---------- */

// 双击「刷新」会连发两个引导：都 setMeta + loadData 虽然能靠 loadSeq 收敛，但没必要
let bootstrapInFlight = false;

/* meta 是唯一引导接口：导航、筛选下拉、榜单控件都要等它就绪。
   失败摆失败空态；用户点「刷新」时 loadData 的守卫会重新走到这里 */
export function bootstrap() {
    if (bootstrapInFlight) return;
    bootstrapInFlight = true;
    return fetchMetaApi()
        .then(body => {
            const data = (body && body.data) || {};
            // 后端契约保证形状；万一拿到 200 但目录为空的坏数据，按失败处理，
            // 别让页面渲染成“未选择分类”——那会被读成“没有书”
            if (!Array.isArray(data.categories) || data.categories.length === 0
                || !Array.isArray(data.rankTypes) || data.rankTypes.length === 0) {
                throw new Error('榜单元信息不完整（分类目录或榜单类型为空）');
            }
            setMeta(data);
            renderNav();
            populateFilterCategories();
            renderRankTypeControls();
            updateStatsMeta();
            loadData();
        })
        .catch(err => {
            console.error('榜单元信息加载失败:', err);
            renderEmptyState('😅', '加载失败，请稍后重试',
                `${(err && err.message) || String(err)}（点右上角「🔄 刷新」重试）`);
        })
        .finally(() => {
            bootstrapInFlight = false;
        });
}

/* keepPage 为真时留在当前页：拉黑/恢复只是让一行进出，不该把用户弹回第 1 页。 */
export function loadData(keepPage) {
    if (!isMetaReady()) {
        // meta 还没就绪（首次引导失败后点刷新会走到这里）：先重新引导，成功后自动续载
        bootstrap();
        return;
    }
    if (!keepPage) {
        state.currentPage = 1;
    }
    /* 每次加载一个序号：同时中止上一个还在飞的请求 */
    const seq = ++loadSeq;
    if (loadController) {
        loadController.abort();
    }
    loadController = new AbortController();
    const signal = loadController.signal;

    renderLoading();

    if (state.currentCategory === 'ALL') {
        const selected = state.selectedCategories;
        if (selected.length === 0) {
            // 一个分类都没选时不去请求后端：否则会被当成“不筛选”而返回全库数据。
            // 作废在飞请求并推进序号：否则此前发出的 allBooks 迟到时 seq 仍相等，
            // 会把“未选择分类”空态盖回旧数据
            ++loadSeq;
            if (loadController) {
                loadController.abort();
            }
            state.lastData = null;
            state.filtersActive = false;
            document.getElementById('totalCount').textContent = 0;
            renderEmptyState('🗂️', '未选择分类', '请在“分类”里至少勾选一项');
            return;
        }
        const wordRange = document.getElementById('filterWord').value || '';
        const status = document.getElementById('filterStatus').value || '';
        state.blockedView = document.getElementById('filterBlocked').checked;
        state.filtersActive = !!(wordRange || status || state.blockedView)
            || selected.length < getDisplayedCategories().length;
        const url = `${API_BASE}/allBooks`
            + `?category=${encodeURIComponent(selected.join(','))}`
            + `&wordRange=${encodeURIComponent(wordRange)}`
            + `&status=${encodeURIComponent(status)}`
            + `&blockedOnly=${state.blockedView}`;

        fetchRankList(url, signal)
            .then(data => {
                if (seq !== loadSeq) return;
                const novels = data.data || [];
                /* 只有“displayed 全选 + 无筛选”时的空列表才说明库是空的，这时才降级到
                   第一个展示中的分类；筛选到 0 行是正常的，不该把用户踢出「全部」视图 */
                if (novels.length === 0 && !state.filtersActive) {
                    const firstCategory = getDisplayedCategories()[0];
                    if (firstCategory) {
                        console.log('数据库无数据，自动切换到分类:', firstCategory.name);
                        switchCategory(firstCategory.code);
                        return;
                    }
                }
                renderBooks(data);
            })
            .catch(err => showLoadError(err, seq));
        return;
    }

    state.blockedView = false;
    state.filtersActive = false;
    /* category 现在传分类 code（如 M_WESTERN_FANTASY）；抓满整个分类（写库），
       之后翻页只切本地数组，不再逐页请求上游 */
    const url = `${API_BASE}?category=${encodeURIComponent(state.currentCategory)}&page=1&size=${WHOLE_LIST_SIZE}`;
    postJson(url, {signal})
        .then(data => {
            if (seq !== loadSeq) return;
            renderBooks(data);
        })
        .catch(err => showLoadError(err, seq));
}

/* 请求失败不再静默切换分类，直接把后端原因显示出来 */
export function showLoadError(err, seq) {
    // 主动中止不是失败；后到的旧响应也不许覆盖新一轮的加载态
    if (err && err.name === 'AbortError') return;
    if (seq !== undefined && seq !== loadSeq) return;
    console.error('加载失败:', err);
    state.lastData = null;
    document.getElementById('totalCount').textContent = 0;
    renderEmptyState('😅', '加载失败，请稍后重试', err.message || String(err));
}

export function switchCategory(code) {
    state.currentCategory = code;
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.code === code);
    });
    const name = categoryDisplayName(code);
    if (name) {
        document.getElementById('currentCategory').textContent = name;
    }
    // 筛选栏仅“全部”视图出现；显隐一律用 hidden，不再碰 style
    document.getElementById('filterBar').hidden = code !== 'ALL';
    loadData(); // 页码重置 1、blockedView 复位都由 loadData 的默认路径完成
}

/* ---------- 榜单类型切换 ---------- */

let rankTypeInFlight = false;

/* 切榜是写库保存：成功后用回包的全量视图校正本地 meta，再重载当前视图。
   新榜单要等重新抓取才会体现，一条 warn toast 说清楚即可 */
export function saveRankType(rankType) {
    if (rankTypeInFlight || !rankType || rankType === getActiveRankType()) return;
    rankTypeInFlight = true;
    setRankTypeControlsDisabled(true);

    saveConfigApi({rankType: rankType})
        .then(body => {
            setMeta((body && body.data) || {});
            renderRankTypeControls(); // 以服务器回包为准校正选中态
            updateStatsMeta();
            const rt = findRankType(getActiveRankType());
            // 不自动 loadData：分类视图的加载是真实抓上游（最坏约 47 秒），“切个选项
            // 就卡死一分钟”比数据旧更伤；库里仍是旧榜，等下一次抓取自然生效
            showToast(`已切换到${rt ? rt.name : rankType}，重新抓取后生效`, 'warn');
        })
        .catch(err => {
            console.error('切换榜单失败:', err);
            showToast('切换榜单失败: ' + err.message, 'error');
            renderRankTypeControls(); // 失败把选中态滚回当前生效的榜单
        })
        .finally(() => {
            rankTypeInFlight = false;
            setRankTypeControlsDisabled(false);
        });
}

/* ---------- 展示分类保存 ---------- */

let displaySaveInFlight = false;

export function saveDisplayCategories(codes) {
    if (displaySaveInFlight) return;
    displaySaveInFlight = true;
    setConfigSaveBusy(true);

    // 与当前 displayed 集逐一对过没差别就不打扰后端，也不把用户视图重拉一遍
    const draftSet = new Set(codes);
    const currentDisplayed = getDisplayedCategories().map(c => c.code);
    const unchanged = currentDisplayed.length === codes.length
        && currentDisplayed.every(code => draftSet.has(code));
    // 记下保存前的 displayed 集：新启用的分类要补进筛选选择集（见 then 里）
    const prevDisplayed = new Set(currentDisplayed);

    const request = unchanged
        ? Promise.resolve(null)
        : saveConfigApi({displayCategories: codes})
            .then(body => setMeta((body && body.data) || {}));

    request
        .then(() => {
            showToast('保存成功', 'success');
            closeConfigPanel();
            if (unchanged) return; // 没改动：不打扰后端，也不把当前视图重拉一遍
            renderNav();
            // 刚启用的分类自动进筛选选择集：用户点开它就是要看，「全部」里立刻可见。
            // 只补 display 状态发生 false→true 翻转的 code，不动用户手动取消勾选的那些
            getDisplayedCategories().forEach(c => {
                if (!prevDisplayed.has(c.code) && !state.selectedCategories.includes(c.code)) {
                    state.selectedCategories.push(c.code);
                }
            });
            populateFilterCategories(); // 顺带裁掉选择集里已不展示的 code
            updateStatsMeta();
            const current = state.currentCategory;
            if (current !== 'ALL') {
                const cat = findCategory(current);
                if (!cat || !cat.displayed) {
                    showToast(`「${categoryDisplayName(current)}」已取消展示，已切回「全部」`, 'warn');
                    switchCategory('ALL'); // 内部会 loadData
                    return;
                }
                return; // 当前分类仍在展示，它的数据不受这次保存影响
            }
            loadData(); // ALL 视图的可选分类集变了，重新拉
        })
        .catch(err => {
            console.error('保存展示分类失败:', err);
            showToast('保存失败: ' + err.message, 'error');
            // 面板保持打开，用户可以直接重试
        })
        .finally(() => {
            displaySaveInFlight = false;
            setConfigSaveBusy(false);
        });
}

/* ---------- 分页 / 筛选（本地派生为主） ---------- */

/* 只有本地已有整榜数据时翻页才有意义 */
export function goToPage(page) {
    if (!state.lastData) return;
    state.currentPage = page;
    renderBooks(state.lastData);
    window.scrollTo({ top: 0 });
}

export function onPageSizeChange() {
    const select = document.getElementById('pageSizeSelect');
    const custom = document.getElementById('customPageSize');
    if (select && custom) {
        custom.hidden = select.value !== 'custom';
    }
    state.currentPage = 1;
    /* 每页条数只是本地切片，不值得再打一次上游 */
    if (state.lastData) {
        renderBooks(state.lastData);
    } else {
        loadData();
    }
}

export function onFilterChange() {
    if (state.currentCategory === 'ALL') {
        loadData();
    }
}

/* ---------- 拉黑 / 恢复 ---------- */
export function setBlocked(bookId, blocked, btnElement) {
    if (!bookId) return;

    const previousText = btnElement.textContent;
    btnElement.disabled = true;
    btnElement.textContent = blocked ? '拉黑中...' : '恢复中...';

    setBlockedApi(bookId, blocked)
        .then(() => loadData(true))
        .catch(err => {
            console.error(blocked ? '拉黑失败:' : '恢复失败:', err);
            showToast((blocked ? '拉黑失败: ' : '恢复失败: ') + err.message, 'error');
            btnElement.disabled = false;
            btnElement.textContent = previousText;
        });
}

/* ---------- 一键获取全部 ---------- */
export function fetchAllData() {
    const btn = document.getElementById('fetchAllBtn');
    if (!btn) return;
    btn.disabled = true;
    btn.textContent = '⏳ 获取中...';

    fetchAllDataApi()
        .then(data => {
            // 注意：这两个数组在后端是信封顶层字段，与 data 平级，不在 data 里
            const failed = data.failedCategories || [];
            const notSaved = data.dbNotSavedCategories || [];
            let notice = '';
            if (failed.length > 0) {
                notice += '部分分类获取失败:\n' + failed.join('\n');
            }
            if (notSaved.length > 0) {
                // 抓到了却没落库 = 数据库没启动，光看 totalBooks 会误以为一切正常
                notice += (notice ? '\n\n' : '') + '以下分类未写入数据库（数据库不可用？）:\n' + notSaved.join('、');
            }
            if (notice) showToast(notice, 'warn', 6000); // 多行信息收进一条 toast
            loadData();
        })
        .catch(err => {
            console.error('一键获取失败:', err);
            showToast('❌ 一键获取失败: ' + err.message, 'error');
        })
        .finally(() => {
            btn.disabled = false;
            btn.textContent = '📥 一键获取全部';
        });
}
