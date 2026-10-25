// @ts-check
/* ============================================================
   js/app.js - 入口与引导
   index.html 只引这一个 <script type="module" src="/js/app.js">
   ============================================================ */

import { state, loadMinRating } from './state.js';
import { bindEvents } from './events.js';
import { bootstrap } from './data.js';
import { loadAiStatus } from './ai_actions.js';

document.addEventListener('DOMContentLoaded', () => {
    // 先挂事件再引导：meta 一到，导航/筛选/榜单控件就有内容并立即拉数据；
    // meta 失败时 bootstrap 自己会摆失败空态，「刷新」按钮经 loadData 守卫重新引导
    document.getElementById('filterBar').hidden = state.currentCategory !== 'ALL';
    // 评分下限先于 bootstrap 应用：首屏请求就带上用户上次填的值
    const ratingInput = /** @type {HTMLInputElement} */ (document.getElementById('filterMinRating'));
    const savedRating = loadMinRating();
    if (ratingInput && savedRating !== null) ratingInput.value = savedRating;
    bindEvents();
    // 引擎状态与榜单并行加载：只影响 AI 控件，不阻塞首屏
    loadAiStatus();
    bootstrap();
});
