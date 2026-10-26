// @ts-check
/* ============================================================
   js/ai_rate.js - 型号的加权平均 AI 率
   ------------------------------------------------------------
   页面上原先显示的百分比只是「某一次审核」的概率。需求要的是
   这本书在这个型号下的综合值：

       综合 AI 率 = Σ(ai_score × 章数) / Σ(章数)

   权重取每轮**实际看了几章**：逐章校验 1 章 = 1 份证据，整本初筛
   按实际采用的章数计。这样一次 10 章初筛不会被一次 1 章校验等权
   冲淡，反过来也不会让一次单章命中独占整个综合值。

   不计入平均的三种行：
   - kind=progress：只推进游标，没有新证据；
   - kind=failed：调用失败，没有概率（ai_score 为空）；
   - ai_score 为空：旧行与失败行，无法参与计算。
   ============================================================ */

/** 三档取样各自固定抽取的章数：head/recent 各 10 章，random 两段各 5 章。 */
const MODE_CHAPTERS = { head: 10, recent: 10, random2x5: 10 };

/**
 * 取样档位 → 该档固定章数。
 *
 * <p>历史档位（mid / mid_recent）的章数随配置变化，不在这里猜；
 * 返回 0 表示"本档位给不出固定值"。</p>
 *
 * @param {string} mode 档位名，如 official:head / third:random2x5
 * @returns {number} 固定章数，未知为 0
 */
export function modeChapterCount(mode) {
  const strategy = String(mode || '').split(':').pop() || '';
  return MODE_CHAPTERS[strategy] || 0;
}

/**
 * 解析取样跨度里的章号个数：1-4,7,12 → 6。
 *
 * <p>sample_span 列宽只有 40，超长会被写入端截断（尾部「…」），
 * 所以解析值只可能偏小——它只用于没有 sample_chapters 的旧行。</p>
 *
 * @param {string} span 章号跨度
 * @returns {number} 章号个数
 */
export function spanChapterCount(span) {
  const text = String(span || '');
  if (!text) return 0;
  let total = 0;
  const pattern = /(\d+)(?:\s*-\s*(\d+))?/g;
  let match = pattern.exec(text);
  while (match) {
    const from = Number(match[1]);
    const to = match[2] === undefined ? from : Number(match[2]);
    if (Number.isFinite(from) && Number.isFinite(to) && to >= from) {
      total += to - from + 1;
    }
    match = pattern.exec(text);
  }
  return total;
}

/**
 * 一轮审核的权重（章数）。
 *
 * <p>优先用写入时直接记下的 sample_chapters；旧行没有该列时，
 * 用「跨度解析值」与「档位固定章数」取较大者——截断只会让解析值偏小，
 * 固定章数是下限。两者都没有（legacy 行）按 1 计，不让它把结果拉偏。</p>
 *
 * @param {{kind?: string, model?: string, sampleChapters?: number|null,
 *          sampleSpan?: string, sampleMode?: string}|null} entry 一轮流水
 * @returns {number} 权重章数，0 表示不参与计算
 */
export function evidenceChapters(entry) {
  if (!entry) return 0;
  const kind = String(entry.kind || '');
  // 进度行与失败行不是证据
  if (kind === 'progress' || kind === 'failed') return 0;
  // 逐章校验一轮只判一章，权重恒为 1
  if (kind === 'verify' || String(entry.model || '').endsWith('#verify')) return 1;
  const recorded = Number(entry.sampleChapters);
  if (Number.isFinite(recorded) && recorded > 0) return recorded;
  const parsed = spanChapterCount(String(entry.sampleSpan || ''));
  const fixed = modeChapterCount(String(entry.sampleMode || ''));
  const best = Math.max(parsed, fixed);
  return best > 0 ? best : 1;
}

/**
 * 一组流水的加权平均 AI 率。
 *
 * @param {Array<{kind?: string, model?: string, aiFlag?: number, aiScore?: number|string|null,
 *                sampleChapters?: number|null, sampleSpan?: string, sampleMode?: string}>} entries
 *        同一「引擎 × 型号」的流水
 * @returns {{rate: number, rounds: number, chapters: number, hits: number}|null}
 *         无可计算的轮次时返回 null
 */
export function weightedAiRate(entries) {
  let weighted = 0;
  let chapters = 0;
  let rounds = 0;
  let hits = 0;
  for (const entry of entries || []) {
    if (!entry) continue;
    const kind = String(entry.kind || '');
    if (kind === 'progress' || kind === 'failed') continue;
    const score = toScore(entry.aiScore);
    if (score === null) continue;
    const weight = evidenceChapters(entry);
    if (weight <= 0) continue;
    weighted += score * weight;
    chapters += weight;
    rounds += 1;
    if (Number(entry.aiFlag) === 2) hits += 1;
  }
  if (chapters === 0) return null;
  return { rate: weighted / chapters, rounds, chapters, hits };
}

/**
 * 概率归一：空值（失败行）与非法值都返回 null，不能当成 0% 计入平均。
 *
 * @param {number|string|null|undefined} value
 * @returns {number|null}
 */
function toScore(value) {
  if (value === null || value === undefined || value === '') return null;
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}
