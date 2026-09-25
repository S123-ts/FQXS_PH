/* ============================================================
   js/app.js - 入口与引导
   index.html 只引这一个 <script type="module" src="/js/app.js">
   ============================================================ */

import { state } from './state.js';
import { bindEvents } from './events.js';
import { bootstrap } from './data.js';

document.addEventListener('DOMContentLoaded', () => {
    // 先挂事件再引导：meta 一到，导航/筛选/榜单控件就有内容并立即拉数据；
    // meta 失败时 bootstrap 自己会摆失败空态，「刷新」按钮经 loadData 守卫重新引导
    document.getElementById('filterBar').hidden = state.currentCategory !== 'ALL';
    bindEvents();
    bootstrap();
});
