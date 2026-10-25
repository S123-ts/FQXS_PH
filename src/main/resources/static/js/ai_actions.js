// @ts-check
/* ============================================================
   js/ai_actions.js - AI 判定动作（从 data.js 拆出）
   引擎/型号选择、批量初筛、校验选中、徽章重跑、逐章校验与熔断；
   榜单加载与分页仍在 data.js。
   ============================================================ */

import { state, getSampleSpec, isForceRejudge } from './state.js';
import { fetchAiStatusApi, sweepFreeApi, detectFreeApi, stepVerifyApi } from './api.js';
import {
    showToast, patchBookRow, escapeText, escapeAttr, setBtnState, renderBooks, formatAiScore,
    verifyProgress
} from './render.js';
import { refreshOpenAiDetail } from './ai_detail.js';
import {
    currentPageBooks, patchLocalBook, upsertLocalVerdict, loadVerdictsForCurrentPage
} from './data.js';


/* 免费引擎熔断：整批一本书都没判成（processed=0 且 failed>0）算一次坏批。
   连续两批就换引擎；错误里出现密钥/限流/额度字样时第一批就换——
   继续重试只会把整轮初筛耗在同一个坏通道上（旧循环在整批失败时还会无限重试）。
   没有这个判断的话，所选引擎一旦持续报错，用户看到的就是进度条永远停在 0。 */
const BAD_BATCH_LIMIT = 2;
/* 「校验选中」的并发判定路数：与后端批量初筛的 sweep-concurrency（默认 3）对齐。
   上游章节请求被 UpstreamThrottle 全局节拍串行化（800ms 一槽，多路只会排队），
   并发的收益在模型调用；每本书的进度与结论按 bookId 独立，互不覆盖 */
const SELECTION_WORKERS = 3;
// 只认引擎/鉴权层面的信号：不能匹配错误文本里的 bookId 数字（曾把含 401 的 id 当限流）
const FATAL_ENGINE_ERROR = /(鉴权失败|上游返回\s*(401|403|429)|余额不足|额度|欠费|未配置\s*API\s*Key)/;
// 书本级错误：试读/目录为空之类换引擎也没用，不该触发熔断
const BOOK_LEVEL_ERROR = /(可用正文不足|章节目录为空|正文为空)/;

/* 本轮还没试过的下一个可用引擎；全试过返回 null（避免在坏引擎之间来回切） */
/* 熔断候选：优先用后端下发的 autoBan 白名单（不允许被自动切换的引擎不参与） */
function bannableEngines() {
    const status = state.aiStatus || {};
    return Array.isArray(status.bannableEngines) && status.bannableEngines.length
        ? status.bannableEngines
        : (status.availableEngines || []);
}

function nextAvailableEngine(current, tried) {
    return bannableEngines().find(engine => engine !== current && !tried.has(engine)) || null;
}

/* 切换引擎时同步下拉与 state：否则按钮上显示的引擎和实际在跑的不是同一个 */
function switchActiveEngine(engine) {
    state.currentEngine = engine;
    const select = /** @type {HTMLSelectElement} */ (document.getElementById('aiEngineSelect'));
    if (select) select.value = engine;
}

/* ---------- AI 文判定 ---------- */

// 初筛与逐页校验各自一个标记：批量判定耗时以十秒计，连点会重复消耗额度
let aiSweepInFlight = false;
let aiPageInFlight = false;
// 运行中的批量任务各自一个中止器：再点一次按钮 = 跑完当前批后停止
let aiSweepAbort = null;
let aiPageAbort = null;
// 手点徽章重跑：按 (bookId, engine) 去重——同一本书同一个引擎重跑中再点才提示，
// 不同书可同时重跑（与逐章校验同一套并行纪律）；防的是手滑连点把同一本书判两遍
const aiRerunInFlight = new Set();

// 未配 Key 的统一引导文案：初筛与校验选中用同一句，行为一致
const NO_ENGINE_HINT = '未配置免费引擎密钥：点右上角「API 密钥」配置任意一个免费引擎，保存即生效';

// 免费引擎的选择框文案：全名进 title，短名进选项
const ENGINE_LABELS = {
    'glm-4-flash': 'GLM（智谱）',
    'qianfan-ernie-speed': 'ERNIE（千帆）',
    'qwen2.5-7b': 'Qwen（硅基流动）'
};

/* 引擎就绪情况与判定分布：填充引擎下拉，并决定是否自动初筛 */
export function loadAiStatus() {
    return fetchAiStatusApi()
        .then(body => {
            state.aiStatus = (body && body.data) || null;
            renderEngineSelect();
            // verifyEngines 到位后再补画一次：首屏 renderBooks 跑在状态回来之前，
            // 不补画的话逐章校验按钮要等下一次翻页/刷新才出现
            if (state.lastData) renderBooks(state.lastData);
            return state.aiStatus;
        })
        .catch(err => {
            // 状态读不到不影响主流程：按钮仍可手动点
            console.warn('AI 状态读取失败:', err);
            state.aiStatus = null;
            return null;
        });
}

/* 引擎下拉只列 Key 已配置的免费引擎（GLM / ERNIE / Qwen） */
function renderEngineSelect() {
    const select = /** @type {HTMLSelectElement} */ (document.getElementById('aiEngineSelect'));
    if (!select) return;
    const engines = (state.aiStatus && state.aiStatus.availableEngines) || [];
    if (engines.length === 0) {
        // 空下拉必须自己说明“为什么空、怎么补”：只写“未配置”的话，
        // 用户点校验按钮只会得到一句失败提示，不知道该去哪配 Key
        select.innerHTML = '<option value="">未配置密钥</option>';
        select.title = '把 ai-keys.bat.example 复制为 ai-keys.bat，填入 GLM_API_KEY 或 QWEN_API_KEY 后重启应用';
        select.disabled = true;
        state.currentEngine = '';
        return;
    }
    select.title = '';
    // 两个以上可用引擎才给「随机」：每次挑没判过这本书的引擎，两份额度都不闲着
    const randomOption = engines.length >= 2
        ? `<option value="random" title="每次从已配 Key 的引擎里随机挑，并优先避开已判过这本书的引擎">🎲 随机（自动分摊）</option>`
        : '';
    select.innerHTML = randomOption + engines
        .map(engine => `<option value="${escapeAttr(engine)}" title="${escapeAttr(ENGINE_LABELS[engine] || engine)}">${escapeText(ENGINE_LABELS[engine] || engine)}</option>`)
        .join('');
    select.disabled = false;
    // 保留用户已选引擎（random 也保留）；否则默认 GLM（免费额度最稳），没有就取第一个
    const preferred = engines.includes('glm-4-flash') ? 'glm-4-flash' : engines[0];
    const keep = state.currentEngine === 'random' || engines.includes(state.currentEngine);
    state.currentEngine = keep ? state.currentEngine : preferred;
    select.value = state.currentEngine;
}

/* 免费批量初筛：一次点击 = 一批（默认 5 本），取当前取样规则。
   不自动续批：while(true) 会一直跑到判完，一次点击即交出上游请求与模型额度，
   中途既无法观察也无法停止。运行中再点一次 = 请求当前批结束后停止
   （已发出的那批后端会跑完，无法中断）。 */
export async function runAiSweep(limit, engine, options) {
    if (aiSweepInFlight) {
        if (aiSweepAbort) aiSweepAbort.abort();
        return null;
    }
    const opts = options || {};
    const interactive = opts.interactive !== false;
    if (interactive && !engine) {
        showToast(NO_ENGINE_HINT, 'warn', 8000);
        return null;
    }

    const btn = document.getElementById('aiSweepBtn');
    // 一次点击只判一批：默认 5 本，硬上限由后端 sweep-limit-max 兜底
    const perBatch = Math.max(1, limit || 0) || 5;
    // 取样规则在点击瞬间快照：跑到一半改下拉不该影响这一批
    const sample = getSampleSpec();
    const randomMode = engine === 'random';
    const enginePool = bannableEngines();
    if (randomMode && enginePool.length === 0) {
        showToast(NO_ENGINE_HINT, 'warn', 8000);
        return null;
    }

    aiSweepInFlight = true;
    aiSweepAbort = new AbortController();
    const signal = aiSweepAbort.signal;
    // 随机模式：这一批从一个可用引擎里随机挑一个（后端批量接口要求明确引擎）
    let activeEngine = randomMode
        ? enginePool[Math.floor(Math.random() * enginePool.length)]
        : engine;
    const triedEngines = new Set([activeEngine]);
    let failoverNote = '';
    let processed = 0;
    let failed = 0;
    let aiCount = 0;
    let suspectCount = 0;
    let remaining = -1;
    let stopped = false;
    if (btn) setBtnState(btn, '⏳', '初筛中...');

    try {
        // 最多两轮：首轮整批失败且是引擎级错误时换一个引擎重试一次，之后无论结果都收工
        for (let attempt = 0; attempt < 2; attempt++) {
            let body;
            try {
                body = await sweepFreeApi(perBatch, {engine: activeEngine, sample}, signal);
            } catch (err) {
                if (isAbortError(err)) {
                    stopped = true;
                    break;
                }
                throw err;
            }
            const result = (body && body.data) || {};
            processed += result.processed || 0;
            failed += result.failed || 0;
            aiCount += result.aiCount || 0;
            suspectCount += result.suspectCount || 0;
            remaining = result.remaining || 0;
            // 判定结论已落库：当前页可见的书直接补拉刷新徽章，不必整页重载
            loadVerdictsForCurrentPage();

            const batchBad = (result.processed || 0) === 0 && (result.failed || 0) > 0;
            const errors = result.errors || [];
            const firstError = errors[0] || '';
            // 整批错误全是书本级（试读/目录空）时换引擎同样失败，只会重复消耗额度
            const bookLevelOnly = batchBad && errors.length > 0
                && errors.every(item => BOOK_LEVEL_ERROR.test(item));
            const engineLevel = batchBad && !bookLevelOnly
                && (FATAL_ENGINE_ERROR.test(firstError) || attempt > 0);
            if (attempt === 0 && engineLevel && !randomMode) {
                const next = nextAvailableEngine(activeEngine, triedEngines);
                if (next) {
                    showToast(`${engineLabel(activeEngine)} 整批失败（${firstError || '上游异常'}），`
                        + `已切换到 ${engineLabel(next)} 重试一次`, 'warn', 8000);
                    triedEngines.add(next);
                    activeEngine = next;
                    switchActiveEngine(next);
                    continue;
                }
                failoverNote = `${engineLabel(activeEngine)} 整批失败，且没有其他可用引擎`;
            }
            break;
        }
    } catch (err) {
        console.error('AI 初筛失败:', err);
        if (interactive) showToast('AI 初筛失败: ' + err.message, 'error', 6000);
        throw err;
    } finally {
        aiSweepInFlight = false;
        aiSweepAbort = null;
        if (btn) setBtnState(btn, '🧪', 'AI 初筛');
        if (interactive) {
            const summary = (stopped ? '初筛已停止：本次已判 ' : '初筛完成：本次判定 ')
                + processed + ' 本（AI ' + aiCount + ' / 疑似 ' + suspectCount + '）'
                + (failed ? `，失败 ${failed}` : '')
                + (remaining > 0 ? `，还剩 ${remaining} 本未判，可再点一次继续` : '')
                + (failoverNote ? `；${failoverNote}` : '');
            showToast(summary, failed ? 'warn' : 'success', 6000);
        }
    }
    return { processed, failed, aiCount, suspectCount, remaining, stopped };
}

/* fetch 被中止抛的是 AbortError：主动停止不是故障，静默结束循环 */
function isAbortError(err) {
    return !!err && (err.name === 'AbortError' || err.code === 20);
}

/* 校验选中：用所选免费引擎逐个判定已勾选的书（支持单选与多选）。
   默认跳过已有该引擎初筛结论的书（后端幂等守卫兜底另一标签页的陈旧缓存）；
   勾了「重判」则选中的全部重走，并带 force=true（明确再消耗一次额度）。
   勾选的书分片后并发判定（默认 3 路，与后端批量初筛的 sweep-concurrency 对齐）：
   上游章节请求被 UpstreamThrottle 全局节拍串行化（800ms 一槽，多路并发也只会排队），
   并发的收益在模型调用；每本书的进度与结论按 bookId 独立，互不覆盖。
   每本完成只原地更新该行的徽章与按钮，不整页重建。
   运行中再点一次按钮 = 判完在飞的本后停止；失败的书没写结论，再点一次只会重判它们 */
export async function runSelectionVerdict() {
    if (aiPageInFlight) {
        if (aiPageAbort) aiPageAbort.abort();
        return;
    }
    if (!state.currentEngine) {
        showToast(NO_ENGINE_HINT, 'warn', 8000);
        return;
    }
    const selection = selectedBooks();
    if (selection.length === 0) {
        showToast('请先勾选要校验的书（可单选或多选）', 'warn', 5000);
        return;
    }
    const engine = state.currentEngine;
    const force = isForceRejudge();
    const sample = getSampleSpec();
    const randomMode = engine === 'random';
    // 连续失败熔断：单本判定没有批次可看，就按连续失败的本数换引擎
    let activeEngine = engine;
    const triedEngines = new Set([engine]);
    let consecutiveFailed = 0;
    // 随机模式不做本地「已判」过滤：由后端逐本挑没判过这本书的引擎，命中缓存也不会再花额度
    const pending = force || randomMode ? selection
        : selection.filter(book => !hasEngineSweepVerdict(book.bookId, engine));
    const skipped = selection.length - pending.length;
    if (pending.length === 0) {
        showToast(`选中的 ${selection.length} 本都已有 ${engineLabel(engine)} 结论，未重复判定`, 'success', 4000);
        return;
    }

    const btn = document.getElementById('aiPageBtn');
    aiPageInFlight = true;
    aiPageAbort = new AbortController();
    const signal = aiPageAbort.signal;

    let done = 0;
    let aiCount = 0;
    let suspectCount = 0;
    let failed = 0;
    let stopped = false;
    /* 判定一本：多个 worker 并发调用。计数是闭包共享变量，JS 单线程下
       await 交错不会丢更新；consecutiveFailed 在并发下是"全局最近连续失败数"
       （任一路失败都累加、任一路成功即清零），熔断意图——密钥/限流/额度问题
       把剩下的书判完——不受影响，反而比单路视角更早发现坏通道 */
    const judgeOne = async (book) => {
        if (signal.aborted) {
            stopped = true;
            return;
        }
        setBtnState(btn, '⏹', `校验中 ${done + 1}/${pending.length}`);
        try {
            const body = await detectFreeApi(book.bookId, {engine: activeEngine, force, sample}, signal);
            const result = (body && body.data) || {};
            // 主表综合列与逐引擎明细都回写本地缓存，翻页后依然正确
            patchLocalBook(book.bookId, {
                aiFlag: result.aiFlag,
                aiScore: result.aiScore,
                aiEngine: result.engine
            });
            upsertLocalVerdict(book.bookId, {
                engine: result.engine,
                aiFlag: result.aiFlag,
                aiScore: result.aiScore,
                reason: result.reason
            });
            if (result.aiFlag === 2) aiCount++;
            else if (result.aiFlag === 3) suspectCount++;
            consecutiveFailed = 0;
        } catch (err) {
            if (isAbortError(err)) {
                stopped = true;
                return;
            }
            failed++;
            consecutiveFailed++;
            console.warn('选中校验失败:', book.bookId, err.message);
            // 连续 3 本失败：多半是密钥/限流问题，换下一个可用引擎把剩下的书判完。
            // 已判过的书不受影响（结论按实际引擎入库），失败的那几本没写结论，再点一次会重判
            if (!randomMode && consecutiveFailed >= 3) {
                const next = nextAvailableEngine(activeEngine, triedEngines);
                if (next) {
                    showToast(`${engineLabel(activeEngine)} 连续失败 ${consecutiveFailed} 本（${err.message}），`
                        + `已切换到 ${engineLabel(next)} 继续`, 'warn', 8000);
                    triedEngines.add(next);
                    activeEngine = next;
                    consecutiveFailed = 0;
                    switchActiveEngine(next);
                }
            }
        }
        done++;
        // 只更新这一行的徽章与按钮：整页重建会让封面重新加载、列表闪一遍
        patchBookRow(book.bookId);
    };
    try {
        // round-robin 分片：比顺序切块均匀，不会让一个 worker 全拿热度高的书
        const workers = Math.min(SELECTION_WORKERS, pending.length);
        const chunks = Array.from({length: workers}, () => []);
        pending.forEach((book, index) => chunks[index % workers].push(book));
        await Promise.all(chunks.map(async (chunk) => {
            for (const book of chunk) {
                await judgeOne(book);
            }
        }));

        const summary = (stopped ? '选中校验已停止：' : '选中校验完成：') + `${done} 本（AI ${aiCount} / 疑似 ${suspectCount}）`
            + (skipped ? `，跳过已判 ${skipped}` : '') + (failed ? `，失败 ${failed}` : '');
        showToast(summary, failed ? 'warn' : 'success', 5000);
    } finally {
        // 循环体里的本地写缓存抛错时也必须复位，否则按钮永久停在「校验中」，再点只能停止
        setBtnState(btn, '🧪', selectionButtonLabel());
        aiPageInFlight = false;
        aiPageAbort = null;
    }
}

/* 选中的书：state.selection 已由 syncBatchBar 裁到当前页，这里再按当前页切片取实体 */
function selectedBooks() {
    const ids = state.selection || new Set();
    if (ids.size === 0) return [];
    return currentPageBooks().filter(book => ids.has(String(book.bookId)));
}

/* 「校验选中」按钮文案：带数量，未选时说明为什么点不动 */
export function selectionButtonLabel() {
    const count = (state.selection && state.selection.size) || 0;
    return count > 0 ? `校验选中(${count})` : '校验选中';
}

/* 点行内模型名（徽章）= 用该引擎 + 当前取样规则强制重跑这本书，并写一条 rerun 流水。
   不看「重判」勾选：手点徽章本身就是显式重跑。
   同一本书同一引擎只跑一个（防手滑连点），不同书同时重跑互不挡 */
export async function runBadgeRerun(bookId, engine, btnElement) {
    if (!bookId || !engine || !btnElement) return;
    const flightKey = verifyFlightKey(bookId, engine);
    if (aiRerunInFlight.has(flightKey)) {
        showToast('这本书的这个引擎正在重跑中，请稍候', 'warn', 4000);
        return;
    }
    const sample = getSampleSpec();
    const label = engineLabel(engine);
    aiRerunInFlight.add(flightKey);
    const previousText = btnElement.textContent;
    btnElement.disabled = true;
    btnElement.textContent = `${label}·重跑中`;
    try {
        const body = await detectFreeApi(bookId, {engine, force: true, sample, rerun: true});
        const result = (body && body.data) || {};
        patchLocalBook(bookId, {
            aiFlag: result.aiFlag,
            aiScore: result.aiScore,
            aiEngine: result.engine
        });
        upsertLocalVerdict(bookId, {
            engine: result.engine,
            aiFlag: result.aiFlag,
            aiScore: result.aiScore,
            reason: result.reason
        });
        patchBookRow(bookId);
        showToast(`${label} 重跑完成：${formatFlag(result.aiFlag)}`
            + (result.aiScore === undefined ? '' : `（风险 ${formatAiScore(result.aiScore)}）`), 'success', 5000);
        refreshOpenAiDetail(bookId);
    } catch (err) {
        btnElement.disabled = false;
        btnElement.textContent = previousText;
        showToast(`重跑失败: ${err.message}`, 'error', 6000);
    } finally {
        aiRerunInFlight.delete(flightKey);
    }
}

/* 结论标记的中文名：toast 里用 */
function formatFlag(flag) {
    if (Number(flag) === 2) return '判为 AI';
    if (Number(flag) === 3) return '判为疑似 AI';
    if (Number(flag) === 1) return '判为非 AI';
    return '结论已更新';
}

/* 本地缓存里该书是否有该引擎的初筛结论（与后端幂等守卫同一判定：
   aiFlag>0 且 chaptersChecked 为空、且不是逐章行） */
function hasEngineSweepVerdict(bookId, engine) {
    const rows = (state.aiVerdicts || {})[bookId] || [];
    // 型号池参与判定：切池后旧型号的结论无效（与后端待判集一致）
    const pools = (state.aiStatus && state.aiStatus.modelPools) || {};
    const pool = pools[engine];
    return rows.some(row => row.engine === engine
        && Number(row.aiFlag) > 0 && !Number(row.chaptersChecked || 0)
        && !String(row.model || '').endsWith('#verify')
        && (!Array.isArray(pool) || pool.length === 0 || !row.model || pool.includes(row.model)));
}

/* 引擎的短名：toast 里提一句时用 */
function engineLabel(engine) {
    if (engine === 'random') return '随机引擎';
    return ENGINE_LABELS[engine] || engine;
}

/* ---------- 逐章校验（点一次跑完整批） ---------- */

/* 兜底步数：10 章 × 型号池，正常路径靠进度自己停，走不到这里 */
const VERIFY_MAX_STEPS = 200;
/* 在飞批次按 (bookId, engine) 去重：同一本书同一个引擎只允许一个循环
   （两个循环会抢同一行游标，等于把同一章判两遍），
   不同书、或同书不同引擎互不挡——审查要能多个同时跑（2026-10-25 用户要求）。
   上游章节请求由后端 UpstreamThrottle 全局节拍串行化，并行打不爆番茄。 */
const verifyBatchInFlight = new Set();

function verifyFlightKey(bookId, engine) {
    return `${bookId}|${engine}`;
}

/* 按钮上的引擎短名：与 render.js 渲染时取同一字段，避免「校验中」时突然换名 */
function verifyEngineName(engine) {
    const list = (state.aiStatus && state.aiStatus.verifyEngines) || [];
    const found = list.find(item => item.id === engine);
    if (found) return found.shortName || found.name || found.id;
    return engineLabel(engine);
}

/* 把一步结果写进本地缓存（与服务端同规则：曾命中就保持命中，进度取 stepIndex） */
function applyVerifyStep(bookId, engine, result, message) {
    // 逐章行按 (engine, model#verify) 独立存：与整本初筛的结论互不覆盖
    const rowModel = result.model ? `${result.model}#verify` : '';
    if (!rowModel) return; // allDone：服务端没有可推进的型号，本地也没有可写的行
    const rows = (state.aiVerdicts || {})[bookId] || [];
    const previous = rows.find(row => row.engine === engine
        && String(row.model || '') === rowModel);
    // 命中过即保持命中：后续章节未命中不能撤销"已判 AI"的结论（与服务端同规则）
    const keptHit = !!previous && Number(previous.aiFlag) === 2;
    upsertLocalVerdict(bookId, {
        engine: engine,
        model: rowModel,
        aiFlag: (result.aiHit || keptHit) ? 2 : (result.finished ? 1 : 0),
        aiScore: keptHit && !result.aiHit ? previous.aiScore : result.aiScore,
        chaptersChecked: Number(result.stepIndex || 0),
        reason: keptHit && !result.aiHit ? previous.reason : (message || '')
    });
    // 只有命中才覆盖主表综合结论；单纯推进进度不动它
    if (result.aiHit) {
        patchLocalBook(bookId, {
            aiFlag: 2,
            aiScore: result.aiScore,
            aiEngine: engine
        });
    }
}

/* 整批结束只弹一条 toast：逐章弹 10 条等于刷屏，没人看得清最后一句 */
function reportVerifyResult(shortName, chapters, hits, failure, allDone) {
    if (failure) {
        const done = chapters > 0 ? `已校验 ${chapters} 章，` : '';
        showToast(`逐章校验中断：${done}${failure.message}`, 'error', 8000);
        return;
    }
    if (chapters === 0) {
        showToast(`${shortName} 没有可校验的章节`, 'warn', 6000);
        return;
    }
    // 型号池未查完时提示可继续点击，避免误认为已查完
    const tail = allDone ? '型号池都已校验完' : `本次校验 ${chapters} 章，再点可继续`;
    showToast(`${shortName} ${tail}（命中 ${hits} 章）`, hits > 0 ? 'warn' : 'success', 7000);
}

/* 逐章校验：点一次按当前取样规则查完一批章节（默认前 10 章），不再一章一点。
   一次点击的章数以服务端返回的 totalChapters 为准（短书不足 10 章就是几章），
   所以按钮上的 n/总数 每点一次固定前进一批；型号池里有几个型号就点几次。

   为什么不一口气把池内所有型号跑完：GLM 池 4 个型号 = 40 次模型调用，
   一次点击消耗 40 次额度过快，且无法中途终止。

   为什么不按"某个型号查完"来停：服务端每一步都会在型号池里重新随机挑型号
   （摊额度），一批 10 章可能落在 2–3 个型号上，`finished` 要等某个型号凑满才置位——
   按它停会让一次点击变成 40 次调用。

   为什么在前端循环：一批 10 章的模型调用要 30–60 秒，塞进一个请求里界面全程没有进度、
   断网则整批作废；逐章请求能实时显示 n/总数，且服务端按 (book, engine, model#verify)
   记游标——中途关页面再点一次从断点继续，已查过的章不会重复消耗额度。
   命中 AI 不再是终态：继续把剩余章节查完，逐章结论写进详情流水。 */
export async function runVerifyBatch(bookId, engine, btnElement) {
    if (!bookId || !btnElement || !engine) return;
    const flightKey = verifyFlightKey(bookId, engine);
    if (verifyBatchInFlight.has(flightKey)) {
        showToast('这本书的这个引擎正在校验中，等它结束再点', 'warn', 4000);
        return;
    }
    verifyBatchInFlight.add(flightKey);
    const sample = getSampleSpec();
    const shortName = verifyEngineName(engine);
    const restingText = btnElement.textContent || shortName;
    const fallbackBatch = verifyProgress(bookId, engine).perModel;
    btnElement.disabled = true;
    btnElement.textContent = `${shortName} 校验中…`;

    let batchSize = 0; // 一批几章：以服务端 totalChapters 为准，第一次响应后确定
    let chapters = 0;
    let hits = 0;
    let stalled = 0;
    let lastDone = verifyProgress(bookId, engine).done;
    let allDone = false;
    let failure = null;

    try {
        for (let step = 0; step < VERIFY_MAX_STEPS; step += 1) {
            const body = await stepVerifyApi(bookId, engine, { sample });
            const result = (body && body.data) || {};
            const message = (body && body.message) || '';
            if (result.model) chapters += 1; // allDone 那一趟没有章节，不计入
            if (!batchSize && Number(result.totalChapters) > 0) {
                batchSize = Number(result.totalChapters);
            }
            applyVerifyStep(bookId, engine, result, message);
            if (result.aiHit) hits += 1;
            allDone = !!result.allDone;

            const progress = verifyProgress(bookId, engine);
            // 循环期间不重绘整行（patchBookRow 会重建按钮，把这条文案冲掉），只改活着的这颗
            btnElement.textContent = `${shortName} 校验中 ${progress.done}/${progress.total}`;
            // 一批查够 / 型号池查完即停止：池内全跑会一次消耗 10 × 型号数 次额度
            if (allDone || chapters >= (batchSize || fallbackBatch) || progress.done >= progress.total) {
                break;
            }
            // 进度没动：该章既未判定也未推进，连续两次即停止，避免额度耗尽
            if (progress.done > lastDone) {
                lastDone = progress.done;
                stalled = 0;
            } else if (++stalled >= 2) {
                break;
            }
        }
    } catch (err) {
        failure = err;
    } finally {
        verifyBatchInFlight.delete(flightKey);
        // 整批结束后统一重绘这一行：徽章、按钮文案与禁用态都按最终状态来。
        // 多批并行时各批只 patch 自己那一行；极端情况下同一本书两个引擎同时在跑，
        // 先结束那批的重建会让另一批的旧按钮元素脱离文档——后续循环写它无害，
        // 文案由下一次 renderBooks / loadVerdicts 重画补正
        patchBookRow(bookId);
        // 行不在当前视图时 patchBookRow 不会重建按钮，兜底恢复，避免卡在「校验中」
        if (document.contains(btnElement)) {
            btnElement.disabled = false;
            btnElement.textContent = restingText;
        }
        refreshOpenAiDetail(bookId);
        reportVerifyResult(shortName, chapters, hits, failure, allDone);
    }
}
