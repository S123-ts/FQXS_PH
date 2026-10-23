// @ts-check
/* ============================================================
   js/data.js - 数据加载与页面动作（控制器层）
   加载竞态的两个标记与加载逻辑同生共死，收在这里不进 state
   ============================================================ */

import { API_BASE, WHOLE_LIST_SIZE } from './config.js';
import {
    fetchRankList, postJson, setBlockedApi, setWatchedApi, fetchAllDataApi,
    fetchMetaApi, saveConfigApi, fetchVerdictsApi
} from './api.js';
import { state, getEffectivePageSize, loadShownPreference, saveShownPreference } from './state.js';
import {
    isMetaReady, setMeta, getDisplayedCategories, findCategory,
    findRankType, getActiveRankType, categoryDisplayName
} from './meta.js';
import {
    renderBooks, renderEmptyState, renderLoading, renderNav,
    populateFilterCategories, renderRankTypeControls,
    setRankTypeControlsDisabled, updateStatsMeta, updateChromeSummary, showToast, patchBookRow,
    setBtnState, setBatchProgress
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
            // 顺序有讲究：先定勾选集（含恢复上次偏好），导航再按勾选集渲染——
            // 勾选集即导航显示集，反过来导航会渲染成空的
            populateFilterCategories();
            restoreShownPreference();
            renderNav();
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

/* 仅引导时套用一次显示偏好：与当前 displayed 求交集，交集为空则维持默认全选。
   不能放进 populateFilterCategories：⚙ 保存路径里“新启用分类自动勾选”会跟它打架 */
function restoreShownPreference() {
    const stored = loadShownPreference();
    if (!stored) return;
    const shown = new Set(getDisplayedCategories().map(c => c.code));
    const restored = stored.filter(code => shown.has(code));
    if (restored.length > 0) {
        state.selectedCategories = restored;
    }
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
            document.getElementById('totalCount').textContent = '0';
            renderEmptyState('🗂️', '未选择分类', '请在“分类”里至少勾选一项');
            return;
        }
        const wordRange = /** @type {HTMLSelectElement} */ (document.getElementById('filterWord')).value || '';
        const status = /** @type {HTMLSelectElement} */ (document.getElementById('filterStatus')).value || '';
        state.blockedView = /** @type {HTMLInputElement} */ (document.getElementById('filterBlocked')).checked;
        state.watchedView = /** @type {HTMLInputElement} */ (document.getElementById('filterWatched')).checked;
        state.filtersActive = !!(wordRange || status || state.blockedView || state.watchedView)
            || selected.length < getDisplayedCategories().length;
        const url = `${API_BASE}/allBooks`
            + `?category=${encodeURIComponent(selected.join(','))}`
            + `&wordRange=${encodeURIComponent(wordRange)}`
            + `&status=${encodeURIComponent(status)}`
            + `&blockedOnly=${state.blockedView}`
            + `&watchedOnly=${state.watchedView}`;

        fetchRankList(url, signal)
            .then(data => {
                if (seq !== loadSeq) return;
                const novels = data.data || [];
                /* 只有“displayed 全选 + 无筛选”时的空列表才需要进一步解释，这时才降级到
                   第一个展示中的分类；筛选到 0 行是正常的，不该把用户踢出「全部」视图。
                   空列表还分两种：库整体为空（首次使用，降级到分类视图去抓数据）与
                   当前榜单分区为空（切了新榜还没抓过，留在原地摆引导空态） */
                if (novels.length === 0 && !state.filtersActive) {
                    if (data.dbEmpty) {
                        const firstCategory = getDisplayedCategories()[0];
                        if (firstCategory) {
                            console.log('数据库无数据，自动切换到分类:', firstCategory.name);
                            switchCategory(firstCategory.code);
                            return;
                        }
                    }
                    // dbEmpty=false：分区空是切榜后的正常状态，不切走，renderBooks 摆引导空态
                }
                renderWithVerdicts(data);
            })
            .catch(err => showLoadError(err, seq));
        return;
    }

    state.blockedView = false;
    state.watchedView = false;
    state.filtersActive = false;
    /* category 现在传分类 code（如 M_WESTERN_FANTASY）；抓满整个分类（写库），
       之后翻页只切本地数组，不再逐页请求上游 */
    const url = `${API_BASE}?category=${encodeURIComponent(state.currentCategory)}&page=1&size=${WHOLE_LIST_SIZE}`;
    postJson(url, {signal})
        .then(data => {
            if (seq !== loadSeq) return;
            renderWithVerdicts(data);
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
    document.getElementById('totalCount').textContent = '0';
    renderEmptyState('😅', '加载失败，请稍后重试', err.message || String(err));
}

export function switchCategory(code) {
    state.currentCategory = code;
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.toggle('active', /** @type {HTMLElement} */ (btn).dataset.code === code);
    });
    const name = categoryDisplayName(code);
    if (name) {
        document.getElementById('currentCategory').textContent = name;
    }
    // 筛选栏仅“全部”视图出现；显隐一律用 hidden，不再碰 style
    document.getElementById('filterBar').hidden = code !== 'ALL';
    updateChromeSummary();
    loadData(); // 页码重置 1、blockedView 复位都由 loadData 的默认路径完成
}

/* ---------- 榜单类型切换 ---------- */

let rankTypeInFlight = false;

/* 切榜是写库保存：成功后用回包的全量视图校正本地 meta。
   「全部」视图是纯读库：库里已有该榜分区就立刻按新榜展示；
   分类视图的加载是真实抓上游（最坏约 47 秒），不自动触发，等下次抓取生效 */
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
            if (state.currentCategory === 'ALL') {
                loadData(); // 读的是当前榜单分区：有数据即现切现显，没数据摆引导空态
                showToast(`已切换到${rt ? rt.name : rankType}`, 'success');
            } else {
                showToast(`已切换到${rt ? rt.name : rankType}，当前分类重新抓取后更新`, 'warn');
            }
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
            const current = state.currentCategory;
            const currentCat = current === 'ALL' ? null : findCategory(current);
            // 刚启用的分类自动进筛选选择集：用户点开它就是要看，「全部」里立刻可见。
            // 只补 display 状态发生 false→true 翻转的 code，不动用户手动取消勾选的那些
            getDisplayedCategories().forEach(c => {
                if (!prevDisplayed.has(c.code) && !state.selectedCategories.includes(c.code)) {
                    state.selectedCategories.push(c.code);
                }
            });
            // 勾选集即导航显示集：正在浏览的分类若仍在展示就强制保留勾选，
            // 否则导航会把它藏掉，用户被困在一个按钮都点不回来的视图里
            if (currentCat && currentCat.displayed && !state.selectedCategories.includes(current)) {
                state.selectedCategories.push(current);
            }
            populateFilterCategories(); // 裁掉选择集里已不展示的 code 并重建下拉
            saveShownPreference(state.selectedCategories);
            renderNav();                // 与新勾选集同步
            updateStatsMeta();
            if (currentCat && !currentCat.displayed) {
                showToast(`「${categoryDisplayName(current)}」已取消展示，已切回「全部」`, 'warn');
                switchCategory('ALL'); // 内部会 loadData
                return;
            }
            if (current !== 'ALL') return; // 当前分类仍在展示，它的数据不受这次保存影响
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
    renderWithVerdicts();
    window.scrollTo({ top: 0 });
}

export function onPageSizeChange() {
    const select = /** @type {HTMLSelectElement} */ (document.getElementById('pageSizeSelect'));
    const custom = /** @type {HTMLInputElement} */ (document.getElementById('customPageSize'));
    if (select && custom) {
        custom.hidden = select.value !== 'custom';
    }
    state.currentPage = 1;
    /* 每页条数只是本地切片，不值得再打一次上游 */
    if (state.lastData) {
        renderWithVerdicts();
    } else {
        loadData();
    }
}

/* 跳页：读 #pageJumpInput 的值。页数以本地 lastData 现算而不是信输入框的 max——
   数据可能在输入后变过；空/非法输入直接忽略，越界钳到 [1, pages] */
export function jumpToPageFromInput() {
    const input = /** @type {HTMLInputElement} */ (document.getElementById('pageJumpInput'));
    if (!input || !state.lastData || !Array.isArray(state.lastData.data)) return;
    const pages = Math.max(1, Math.ceil(state.lastData.data.length / getEffectivePageSize()));
    const target = parseInt(input.value, 10);
    if (isNaN(target)) return;
    goToPage(Math.min(pages, Math.max(1, target)));
}

export function onFilterChange() {
    if (state.currentCategory === 'ALL') {
        loadData();
    }
}

/* ---------- 拉黑 / 恢复 ---------- */

/* 书名只用于提示文案：列表里找不到（已被筛掉/翻页）就退回 bookId，
   不该为了拼一句话再请求一次 */
function bookTitleOf(bookId) {
    const rows = (state.lastData && state.lastData.data) || [];
    const found = rows.find(item => String(item.bookId) === String(bookId));
    return found && found.title ? found.title : String(bookId);
}

/* 撤销：把刚拉黑的那本恢复回来。失败要说清楚，否则用户以为撤销成功了 */
function undoBlock(bookId) {
    setBlockedApi(bookId, false)
        .then(() => {
            loadData(true);
            showToast('已撤销拉黑', 'success', 3000);
        })
        .catch(err => {
            console.error('撤销拉黑失败:', err);
            showToast('撤销失败: ' + err.message, 'error', 6000);
        });
}

export function setBlocked(bookId, blocked, btnElement) {
    if (!bookId) return;

    const previousText = btnElement.textContent;
    const title = bookTitleOf(bookId);
    btnElement.disabled = true;
    btnElement.textContent = blocked ? '拉黑中...' : '恢复中...';

    setBlockedApi(bookId, blocked)
        .then(() => {
            loadData(true);
            /* 拉黑是不可逆的一步到位操作（掉榜后按 bookId 全行联动），
               所以给 6 秒撤销窗口；恢复本来就是安全方向，只提示不提供撤销 */
            if (blocked) {
                showToast(`已拉黑《${title}》`, 'success', 6000, {
                    label: '撤销',
                    onClick: () => undoBlock(bookId)
                });
            } else {
                showToast(`已恢复《${title}》`, 'success', 4000);
            }
        })
        .catch(err => {
            console.error(blocked ? '拉黑失败:' : '恢复失败:', err);
            showToast((blocked ? '拉黑失败: ' : '恢复失败: ') + err.message, 'error');
            btnElement.disabled = false;
            btnElement.textContent = previousText;
        });
}

/* 关注：与拉黑共用「按 bookId 全行联动」的语义，但不设撤销窗口——
   关注误点再点一下就取消，没有不可逆的代价，二段确认反而碍事 */
export function setWatched(bookId, watched, btnElement) {
    if (!bookId) return;

    const previousText = btnElement.textContent;
    const title = bookTitleOf(bookId);
    btnElement.disabled = true;
    btnElement.textContent = watched ? '关注中...' : '取消中...';

    setWatchedApi(bookId, watched)
        .then(() => {
            loadData(true);
            showToast(watched ? `已关注《${title}》` : `已取消关注《${title}》`, 'success', 3000);
        })
        .catch(err => {
            console.error(watched ? '关注失败:' : '取消关注失败:', err);
            showToast((watched ? '关注失败: ' : '取消关注失败: ') + err.message, 'error');
            btnElement.disabled = false;
            btnElement.textContent = previousText;
        });
}

/* 批量拉黑/恢复：串行逐本调既有单本接口，不新增后端契约。   串行而非并发，是为了让进度可读、也避免一瞬间打出一串写请求 */
export async function runBatchBlock(blocked) {
    const ids = Array.from(state.selection);
    if (ids.length === 0) return;

    setBatchProgress(0, ids.length);
    let ok = 0;
    const failed = [];
    for (const id of ids) {
        try {
            await setBlockedApi(id, blocked);
            ok++;
        } catch (err) {
            failed.push(`${bookTitleOf(id)}：${err && err.message ? err.message : err}`);
        }
        setBatchProgress(ok + failed.length, ids.length);
    }

    state.selection.clear();
    try {
        await loadData(true);
    } finally {
        setBatchProgress(0, 0);
    }

    const verb = blocked ? '拉黑' : '恢复';
    if (failed.length === 0) {
        showToast(`已${verb} ${ok} 本`, 'success', 4000);
    } else {
        showToast(`已${verb} ${ok} 本，失败 ${failed.length} 本\n${failed.slice(0, 5).join('\n')}`,
            'warn', 8000);
    }
}

/* ---------- 一键获取全部 ---------- */
export function fetchAllData() {
    const btn = /** @type {HTMLButtonElement} */ (document.getElementById('fetchAllBtn'));
    if (!btn) return;
    btn.disabled = true;
    setBtnState(btn, '⏳', '获取中...');

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
            setBtnState(btn, '📥', '一键获取全部');
        });
}


/* 拉当前页书籍的逐引擎结论，到位后重绘一次把徽章补上 */
/* 引擎结论补拉序号：切分类/翻页后旧请求慢返回，不能覆盖新一页的缓存 */
let verdictsSeq = 0;

export function loadVerdictsForCurrentPage() {
    const bookIds = currentPageBooks().map(book => book.bookId);
    if (bookIds.length === 0) return Promise.resolve();
    const seq = ++verdictsSeq;
    return fetchVerdictsApi(bookIds)
        .then(body => {
            if (seq !== verdictsSeq) return;
            state.aiVerdicts = (body && body.data) || {};
            /* 结论到位后默认整页 renderBooks 会把封面 <img> 全部销毁重载，列表「再闪一下」。
               当前 DOM 行与当前页 bookId 序列一致时（加载/翻页后的正常路径）改为逐行
               patchBookRow：只更新行内动作簇（徽章/详情/校验），封面不动、无二次闪烁。
               行序列不一致（数据被换过）才回退整页重绘。 */
            const domIds = Array.from(document.querySelectorAll('#bookList .book-item[data-book-id]'))
                .map(el => el.getAttribute('data-book-id') || '');
            const sameRows = domIds.length === bookIds.length
                && domIds.every((id, i) => id === String(bookIds[i]));
            if (sameRows) {
                domIds.forEach(id => patchBookRow(id));
            } else if (state.lastData) {
                renderBooks(state.lastData);
            }
        })
        .catch(err => console.warn('引擎结论读取失败:', err));
}

/* 渲染 + 补拉引擎结论：loadData / 翻页 / 改每页条数统一走这里。
   data 传入“刚拿到的响应体”，翻页这类本地重绘不传、沿用 state.lastData——
   首屏 state.lastData 还是 null，漏传会让 renderBooks 读到空对象而炸 */
export function renderWithVerdicts(data) {
    renderBooks(data || state.lastData);
    loadVerdictsForCurrentPage();
}

/* 当前页（本地切片）里有 bookId 的书：翻页/每页条数变化时它跟着变 */
export function currentPageBooks() {
    if (!state.lastData || !Array.isArray(state.lastData.data)) return [];
    const size = getEffectivePageSize();
    const from = (state.currentPage - 1) * size;
    return state.lastData.data.slice(from, from + size).filter(book => book.bookId);
}

/* 同一本书可能挂在多个分类行：结论按 bookId 回写到本地缓存 */
export function patchLocalBook(bookId, patch) {
    if (!state.lastData || !Array.isArray(state.lastData.data)) return;
    state.lastData.data.forEach(book => {
        if (String(book.bookId) === String(bookId)) {
            Object.assign(book, patch);
        }
    });
}

/* 逐引擎结论按 (bookId, engine) upsert：同引擎新结论覆盖旧行 */
export function upsertLocalVerdict(bookId, verdictRow) {
    if (!bookId || !verdictRow || !verdictRow.engine) return;
    const existing = state.aiVerdicts[bookId] || [];
    /* 同一引擎可能有两条并存的结论行：整本初筛（model 不带后缀）与逐章校验
       （model 形如 glm-4-flash#verify）。按 model 区分，否则逐章进度会把初筛结论覆盖掉，
       徽章与按钮就会显示两个互相矛盾的数字。
       未带 model 的入参（初筛结果）只匹配"非 #verify"的行。 */
    const incoming = String(verdictRow.model || '');
    const index = existing.findIndex(item => {
        if (item.engine !== verdictRow.engine) return false;
        const current = String(item.model || '');
        if (incoming) return current === incoming;
        return !current.endsWith('#verify');
    });
    if (index >= 0) {
        existing[index] = Object.assign({}, existing[index], verdictRow);
    } else {
        existing.push(verdictRow);
    }
    state.aiVerdicts[bookId] = existing;
}
