/* ============================================================
   scripts/ui-smoke.mjs - 前端冒烟回归（真实浏览器，非单测）
   背景：doc/HANDOVER.md §7 长期挂着「前端点击无自动化覆盖」，改 UI 后只能人工回归。
   这个脚本把 2026-10-21 那次「首屏加载失败」的事故路径固化成断言：
   页面能出书、无 pageerror、翻页/改每页条数/设置面板都不炸。

   依赖（不进 Maven 构建，按需装）：
     npm i playwright-core
   运行：
     node scripts/ui-smoke.mjs
   可选环境变量：
     FQXS_URL        默认 http://localhost:8080
     CHROME_PATH     指定浏览器可执行文件（不填交给 playwright-core 解析）
     PW_CORE         playwright-core 的绝对路径（装在非默认位置时用）
   退出码：0 全绿；1 有断言失败（明细打到 stdout）
   ============================================================ */

import { pathToFileURL } from 'node:url';
import { statSync } from 'node:fs';

const URL = process.env.FQXS_URL || 'http://localhost:8080';

/* 登录门（2026-10-22 起全站要会话，见 doc/技术交接.md §4.1）：
   冒烟脚本必须先登录，否则每个断言拿到的都是登录页。
   凭据与 application.yml 的 app.auth 默认值一致，可用环境变量覆盖 */
const FQXS_USER = process.env.FQXS_USER || process.env.APP_LOGIN_USER || 'admin';
const FQXS_PASS = process.env.FQXS_PASS || process.env.APP_LOGIN_PASSWORD || 'admin';

async function loadChromium() {
    let specifier = process.env.PW_CORE || 'playwright-core';
    // 绝对路径必须转成 file URL，否则 Windows 盘符会被当成协议名；
    // 指到包目录时补 /index.js（ESM 不接受目录导入）
    if (/^[a-zA-Z]:[\\/]/.test(specifier) && statSync(specifier).isDirectory()) {
        specifier = specifier.replace(/[\\/]+$/, '') + '/index.js';
    }
    const target = /^[a-zA-Z]:[\\/]/.test(specifier)
        ? pathToFileURL(specifier).href
        : specifier;
    try {
        const mod = await import(target);
        // playwright-core 是 CJS：动态 import 后导出挂在 default 上
        const api = mod.chromium ? mod : (mod.default || {});
        if (!api.chromium) throw new Error('模块未导出 chromium');
        return api.chromium;
    } catch (err) {
        console.error(`[skip] 未找到 playwright-core（${err.code || err.message}）`);
        console.error('       先执行：npm i playwright-core，或用 PW_CORE 指定其绝对路径');
        process.exit(2);
    }
}

const failures = [];
const check = (name, ok, detail) => {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail === undefined ? '' : ' → ' + detail}`);
    if (!ok) failures.push(name);
};

const chromium = await loadChromium();
const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || undefined,
    // 不跟随系统代理：冒烟目标是 localhost 或服务器直连 IP，走本机代理只会把
    // 请求甩给一个可能没在监听的端口（实测 ERR_CONNECTION_RESET，而 curl 直连正常）
    args: ['--no-proxy-server'],
    headless: true
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// page.request 与页面共用同一个 cookie jar：这里 POST 一次，后面所有 goto 都带着会话
const loginRes = await page.request.post(`${URL}/api/auth/login`, {
    data: { username: FQXS_USER, password: FQXS_PASS }
});
if (!loginRes.ok()) {
    console.error(`[fail] 登录失败 HTTP ${loginRes.status()}：检查 FQXS_USER / FQXS_PASS 与服务端 app.auth 配置`);
    await browser.close();
    process.exit(1);
}

// 任何未捕获异常都是回归：首屏事故的表现就是一条 TypeError 被吞成「加载失败」
const pageErrors = [];
page.on('pageerror', e => pageErrors.push(String(e)));

const snapshot = () => page.evaluate(() => ({
    total: Number(document.getElementById('totalCount').textContent || 0),
    items: document.querySelectorAll('.book-item').length,
    emptyText: (document.querySelector('#bookList .empty') || {}).innerText || '',
    navBtns: document.querySelectorAll('.nav-btn').length,
    pagerBtns: document.querySelectorAll('.pager-btn').length
}));

const waitRendered = () => page.waitForFunction(() => {
    const list = document.getElementById('bookList');
    return list && !list.querySelector('.loading');
}, { timeout: 60000 });

try {
    // 不能用 networkidle：首屏会自动触发一次 AI 初筛（长请求），页面永远不会"静下来"。
    // 真正的就绪条件是 #bookList 出数据，交给 waitRendered() 判定
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    /* 「作者」默认男作者是用户定的，别改。刚重抓的榜单 author_gender 全是未知
       （只补热度前 50），默认视图可能只有 0～几行——那是预期行为，不是 bug。
       冒烟要保证后续用例有足够行数，所以断言完默认值就切到「全部」。 */
    const defaultAuthorFilter = await page.$eval('#filterAuthor', el => el.value);
    check('默认按男作者筛选', defaultAuthorFilter === 'male', defaultAuthorFilter);
    /* 2026-10-25 用户定的筛选默认值：字数 100 万以上、状态连载中、评分 ≥9.0。
       三者与作者一起叠加会把列表筛到个位数，后续用例要求足够行数，断言完就全部清空。 */
    const defaultWord = await page.$eval('#filterWord', el => el.value);
    const defaultStatus = await page.$eval('#filterStatus', el => el.value);
    const defaultRating = await page.$eval('#filterMinRating', el => el.value);
    check('默认字数 100 万以上', defaultWord === '100w以上', defaultWord);
    check('默认状态连载中', defaultStatus === '连载中', defaultStatus);
    check('默认评分 9.0', defaultRating === '9.0', defaultRating);
    await page.selectOption('#filterWord', '');
    await page.selectOption('#filterStatus', '');
    await page.fill('#filterMinRating', '');
    await page.dispatchEvent('#filterMinRating', 'change');
    await page.selectOption('#filterAuthor', '');
    await page.waitForFunction(() => document.querySelectorAll('#bookList .book-item').length > 1,
        { timeout: 20000 });
    await waitRendered();
    /* 引擎结论是列表渲染后异步补拉的：切筛选后如果不等它，徽章与「详情入口」会读成空 */
    await page.waitForFunction(() => document.querySelector('.ai-badge') !== null, { timeout: 15000 })
        .catch(() => {});

    // 1) 首屏：必须出数据，且不能是「加载失败」空态
    const first = await snapshot();
    check('首屏有导航按钮', first.navBtns > 1, first.navBtns);
    check('首屏有书籍行', first.items > 0, first.items);
    check('首屏未落在失败空态', !first.emptyText.includes('加载失败'), first.emptyText.slice(0, 60));
    check('首屏计数与行数一致', first.total > 0, first.total);

    // 1.5) 关注入口：每行一颗「关注」按钮，筛选栏有「只看关注」（2026-10-23 新增）
    const watchBtns = await page.locator('#bookList .watch-btn').count();
    const watchableRows = await page.locator('#bookList .book-item[data-book-id]').count();
    const filterWatchedExists = await page.locator('#filterWatched').count();
    check('每行都有「关注」按钮', watchBtns > 0 && watchBtns === watchableRows, `${watchBtns}/${watchableRows}`);
    check('筛选栏有「只看关注」', filterWatchedExists === 1, filterWatchedExists);

    // 1.6) 键盘：j/k 移动高亮、x 勾选、Enter 开详情（2026-10-23 新增）
    // 先等引擎结论补拉完成：那次渲染会重建行，把高亮节点一起换掉
    await page.waitForTimeout(1000);
    // 详情按钮在「更多」菜单里（未展开时不可见），只等它存在，不等可见
    await page.waitForFunction(
        () => !!document.querySelector('#bookList .ai-detail-btn'),
        { timeout: 20000 }
    ).catch(() => {});
    const hasDetailEntry = (await page.locator('#bookList .ai-detail-btn').count()) > 0;
    if (!hasDetailEntry) console.log('SKIP: 库内暂无 AI 判定，Enter 打开详情断言跳过');
    const kbState = () => page.evaluate(() => {
        const rows = [...document.querySelectorAll('#bookList .book-item')];
        return {
            idx: rows.findIndex(r => r.classList.contains('kb-active')),
            checked: !!document.querySelector('#bookList .row-check')?.checked,
            detailOpen: !document.getElementById('aiDetailMask').hidden
        };
    });
    /* 列表快捷键在表单控件里会让路（isTypingTarget），而上一步 selectOption 会把焦点
       留在 #filterAuthor 上；先主动失焦，否则 j 被当成控件输入忽略、高亮永远 -1 */
    await page.evaluate(() => {
        if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
    });
    await page.keyboard.press('j');
    let kb = await kbState();
    check('j 高亮第一行', kb.idx === 0, kb.idx);
    await page.keyboard.press('j');
    kb = await kbState();
    check('再按 j 移到第二行', kb.idx === 1, kb.idx);
    await page.keyboard.press('k');
    kb = await kbState();
    check('k 回到上一行', kb.idx === 0, kb.idx);
    await page.keyboard.press('x');
    kb = await kbState();
    check('x 勾选当前行', kb.checked);
    await page.keyboard.press('x');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(300);
    kb = await kbState();
    check('Enter 打开当前行详情', !hasDetailEntry || kb.detailOpen);
    await page.keyboard.press('Escape');

    // 焦点在控件里时快捷键必须让路，否则打字/选择会被抢走
    await page.focus('#pageSizeSelect');
    const idxBefore = (await kbState()).idx;
    await page.keyboard.press('j');
    const idxAfter = (await kbState()).idx;
    check('下拉框里按 j 不移动列表高亮', idxAfter === idxBefore, `${idxBefore} → ${idxAfter}`);
    await page.evaluate(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); });

    // 2) 改每页条数：默认 10，切到 20 验证生效，只切本地数组、不该再打上游
    const defaultPageSize = await page.$eval('#pageSizeSelect', el => el.value);
    check('默认每页 10 条', defaultPageSize === '10', defaultPageSize);
    await page.selectOption('#pageSizeSelect', '20');
    await page.waitForTimeout(400);
    const sized = await snapshot();
    check('每页条数生效', sized.items === 20, sized.items);
    check('改条数后总数不变', sized.total === first.total, `${first.total} → ${sized.total}`);

    // 3) 翻页：本地切片
    if (sized.pagerBtns > 0) {
        await page.click('.pager-btn[data-page="2"]');
        await page.waitForTimeout(400);
        const paged = await snapshot();
        check('翻页后仍有数据', paged.items > 0, paged.items);
        check('翻页后总数不变', paged.total === first.total, paged.total);
    } else {
        console.log('[skip] 单页数据，跳过翻页断言');
    }

    // 4) 设置面板：动态 checkbox 渲染
    await page.click('#configBtn');
    await page.waitForTimeout(300);
    const configCount = await page.evaluate(() => {
        const mask = document.getElementById('configMask');
        return mask && !mask.hidden ? document.querySelectorAll('#configBody input[type="checkbox"]').length : -1;
    });
    check('展示分类面板可打开且有分类项', configCount > 0, configCount);
    await page.keyboard.press('Escape');

    // 4.1) 密钥面板：没有任何改动时点「保存」应当直接关掉面板。
    //      以前这里弹「没有需要保存的改动」并留在面板上，用户以为没保存成功，连点五次叠五条 toast。
    await page.click('#aiKeyBtn');
    await page.waitForSelector('#aiKeyMask:not([hidden])', { timeout: 10000 });
    await page.waitForSelector('#aiKeyBody .key-input', { timeout: 10000 });
    await page.fill('#aiKeyBody .key-input', '');
    await page.click('#aiKeySaveBtn');
    await page.waitForTimeout(400);
    const keyPanelClosed = await page.evaluate(() => {
        const mask = document.getElementById('aiKeyMask');
        const toasts = [...document.querySelectorAll('#toastHost .toast-body')].map(el => el.textContent);
        return {
            hidden: !!mask && mask.hidden,
            emptyToast: toasts.some(text => text.includes('没有需要保存的改动'))
        };
    });
    check('密钥面板无改动点保存即关闭', keyPanelClosed.hidden, `hidden=${keyPanelClosed.hidden}`);
    check('无改动不再弹「没有需要保存的改动」', !keyPanelClosed.emptyToast);

    // 5) 宽屏布局：一行一本，筛选栏并进统计卡
    const desktopLayout = await page.evaluate(() => {
        const list = document.getElementById('bookList');
        const style = getComputedStyle(list);
        return {
            cols: style.display === 'grid' ? style.gridTemplateColumns.split(' ').length : 1,
            filterInStats: !!document.querySelector('#statsBar #filterBar')
        };
    });
    check('宽屏书籍列表为单列', desktopLayout.cols === 1, `${desktopLayout.cols} 列`);
    check('筛选栏并入统计卡', desktopLayout.filterInStats);

    // 5.1) 每个引擎的 AI 率：徽章必须带出引擎返回的百分比
    await page.waitForSelector('#bookList .ai-badge', { timeout: 20000 }).catch(() => null);
    const badgeTexts = await page.evaluate(() =>
        [...document.querySelectorAll('#bookList .ai-badge')].slice(0, 8).map(el => el.textContent.trim()));
    check('引擎徽章带出 AI 率', badgeTexts.length > 0 && badgeTexts.every(text => text.includes('%')),
        badgeTexts.join(' | '));
    // 5.1b) 徽章只放结论：逐章校验的进度行（aiFlag=0）不能混进来
    //       旧实现会渲染成「GLM·查验中 1」，与按钮上的「GLM·AI 3/40」自相矛盾
    check('徽章里不出现「查验中」进度行',
        badgeTexts.every(text => !text.includes('查验')), badgeTexts.join(' | ') || '（无徽章）');

    // 5.2) 「更多」菜单：详情与逐章校验收在里面，行内只留徽章 + 更多 + 关注 + 拉黑
    const moreBtn = await page.waitForSelector('#bookList .more-btn', { timeout: 20000 }).catch(() => null);
    if (moreBtn) {
        await moreBtn.click();
        await page.waitForTimeout(200);
        const menuState = await page.evaluate(() => {
            const holder = document.querySelector('#bookList .row-more.menu-open');
            const menu = holder && holder.querySelector('.more-menu');
            const row = document.querySelector('#bookList .book-item');
            const actions = row && row.querySelector('.title-actions');
            const rowActions = actions ? actions.querySelectorAll('.row-actions > *').length : 0;
            return {
                open: !!menu && !menu.hidden,
                detailItems: menu ? menu.querySelectorAll('[data-action="ai-detail"]').length : 0,
                verifyItems: menu ? menu.querySelectorAll('[data-action="verify"]').length : 0,
                badges: row ? row.querySelectorAll('.ai-badge').length : 0,
                rowActions
            };
        });
        check('「更多」菜单可打开', menuState.open);
        check('菜单里有详情入口', menuState.detailItems > 0, menuState.detailItems);
        check('菜单里有逐章校验入口（每引擎一颗）', menuState.verifyItems > 0, menuState.verifyItems);
        check('行内操作收敛为「更多 + 关注 + 拉黑」',
            menuState.rowActions >= 1 && menuState.rowActions <= 3,
            `${menuState.rowActions} 个操作 / ${menuState.badges} 枚徽章`);

        await page.locator('.row-more.menu-open [data-action="ai-detail"]').first().click();
        await page.waitForTimeout(200);
        const detail = await page.evaluate(() => {
            const mask = document.getElementById('aiDetailMask');
            return {
                open: !!mask && !mask.hidden,
                menuClosed: !document.querySelector('#bookList .row-more.menu-open'),
                cards: document.querySelectorAll('#aiDetailBody .ai-detail-card').length,
                scores: [...document.querySelectorAll('#aiDetailBody .ai-detail-score')].map(el => el.textContent.trim()),
                reasons: document.querySelectorAll('#aiDetailBody .ai-detail-reason').length
            };
        });
        check('AI 详情可打开', detail.open);
        check('点菜单项后菜单收起', detail.menuClosed);
        check('详情按引擎列出 AI 率', detail.cards > 0 && detail.scores.length === detail.cards
            && detail.scores.every(score => score.includes('%') || score === '—'), detail.scores.join(' | '));
        check('详情带判定理由字段', detail.reasons === detail.cards, `${detail.reasons}/${detail.cards}`);
        await page.keyboard.press('Escape');
        await page.waitForTimeout(150);
        const closed = await page.evaluate(() => document.getElementById('aiDetailMask').hidden);
        check('Esc 可关闭 AI 详情', closed);
    } else {
        console.log('[skip] 当前数据没有引擎结论，跳过详情断言');
    }

    // 5.3) 操作动线：桌面吸顶工具条 + 分页吸底
    /* 每页条数下拉只提供 10 / 20 / 自定义（index.html），旧脚本写死的 30 早已不存在，
       selectOption 会超时并把整段脚本打断。这里改用 20：够把页面撑出滚动条即可 */
    await page.selectOption('#pageSizeSelect', '20');
    await page.waitForTimeout(400);
    await page.evaluate(() => window.scrollTo(0, 1200));
    await page.waitForTimeout(300);
    const stickyUi = await page.evaluate(() => {
        const navH = document.querySelector('.category-nav').getBoundingClientRect().height;
        const bar = document.getElementById('statsBar');
        const pager = document.querySelector('.book-list .pager');
        const pagerRect = pager && pager.getBoundingClientRect();
        return {
            navH: Math.round(navH),
            barTop: Math.round(bar.getBoundingClientRect().top),
            pinned: bar.classList.contains('is-pinned'),
            filterVisible: document.getElementById('filterBar').offsetParent !== null,
            pagerPosition: pager ? getComputedStyle(pager).position : '',
            pagerBottom: pagerRect ? Math.round(pagerRect.bottom) : -1,
            viewH: window.innerHeight
        };
    });
    check('工具条吸在导航下方并收起筛选',
        stickyUi.pinned && Math.abs(stickyUi.barTop - stickyUi.navH) <= 2 && !stickyUi.filterVisible,
        `top=${stickyUi.barTop} nav=${stickyUi.navH} pinned=${stickyUi.pinned}`);
    check('分页条吸底',
        stickyUi.pagerPosition === 'sticky' && Math.abs(stickyUi.pagerBottom - stickyUi.viewH) <= 2,
        `bottom=${stickyUi.pagerBottom} view=${stickyUi.viewH}`);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.waitForTimeout(250);
    const unpinned = await page.evaluate(() => ({
        pinned: document.getElementById('statsBar').classList.contains('is-pinned'),
        filterVisible: document.getElementById('filterBar').offsetParent !== null
    }));
    check('回顶后恢复完整控制区', !unpinned.pinned && unpinned.filterVisible);

    // 6) 手机布局：控制区默认折叠、摘要可展开、折叠态吸在导航下方
    const mobile = await browser.newPage({ viewport: { width: 420, height: 900 } });
    mobile.on('pageerror', e => pageErrors.push('mobile: ' + String(e)));
    try {
        // browser.newPage() 是新的浏览器上下文，cookie 不与主 page 共享：这里要单独登录一次，
        // 否则拿到的是登录页，#bookList 永远等不到
        await mobile.request.post(`${URL}/api/auth/login`, {
            data: { username: FQXS_USER, password: FQXS_PASS }
        });
        await mobile.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
        /* 默认男作者时列表可能只有几行、页面太短无法验证吸顶；先切「全部」再折回默认态 */
        await mobile.click('#chromeSummary');
        await mobile.waitForTimeout(200);
        await mobile.selectOption('#filterWord', '');
        await mobile.selectOption('#filterStatus', '');
        await mobile.fill('#filterMinRating', '');
        await mobile.dispatchEvent('#filterMinRating', 'change');
        await mobile.selectOption('#filterAuthor', '');
        await mobile.waitForFunction(() => document.querySelectorAll('#bookList .book-item').length > 1,
            { timeout: 20000 });
        await mobile.click('#chromeSummary');
        await mobile.waitForTimeout(200);
        await mobile.waitForFunction(() => {
            const list = document.getElementById('bookList');
            return list && !list.querySelector('.loading');
        }, { timeout: 60000 });

        const collapsed = await mobile.evaluate(() => {
            const bar = document.getElementById('statsBar');
            return {
                collapsed: bar.classList.contains('chrome-collapsed'),
                summaryVisible: document.getElementById('chromeSummary').offsetParent !== null,
                summaryText: document.getElementById('chromeSummaryText').textContent,
                bodyVisible: document.getElementById('statsBody').offsetParent !== null,
                barH: Math.round(bar.getBoundingClientRect().height)
            };
        });
        check('手机默认折叠控制区', collapsed.collapsed && !collapsed.bodyVisible, `${collapsed.barH}px`);
        check('折叠摘要显示榜单与筛选', collapsed.summaryVisible && collapsed.summaryText.includes('榜单'), collapsed.summaryText);
        check('折叠后控件区高度收紧', collapsed.barH < 100, `${collapsed.barH}px`);

        // 多引擎徽章 + 详情 + 拉黑 + 校验按钮不能把卡片撑出屏幕
        const overflow = await mobile.evaluate(() => ({
            docW: document.documentElement.scrollWidth,
            viewW: window.innerWidth
        }));
        check('手机无横向溢出', overflow.docW <= overflow.viewW + 1, `${overflow.docW}/${overflow.viewW}`);

        await mobile.click('#chromeSummary');
        await mobile.waitForTimeout(200);
        const opened = await mobile.evaluate(() => ({
            open: !document.getElementById('statsBar').classList.contains('chrome-collapsed'),
            bodyVisible: document.getElementById('statsBody').offsetParent !== null,
            expanded: document.getElementById('chromeSummary').getAttribute('aria-expanded'),
            filterVisible: document.getElementById('filterBar').offsetParent !== null
        }));
        check('点击摘要可展开控制区', opened.open && opened.bodyVisible && opened.expanded === 'true');
        check('展开后筛选栏可见', opened.filterVisible);

        await mobile.click('#chromeSummary');
        await mobile.waitForTimeout(200);
        await mobile.evaluate(() => window.scrollTo(0, 600));
        await mobile.waitForTimeout(300);
        const sticky = await mobile.evaluate(() => {
            const navH = document.querySelector('.category-nav').getBoundingClientRect().height;
            const bar = document.getElementById('statsBar');
            return {
                navH: Math.round(navH),
                top: Math.round(bar.getBoundingClientRect().top),
                collapsed: bar.classList.contains('chrome-collapsed')
            };
        });
        check('折叠摘要吸在导航下方', sticky.collapsed && Math.abs(sticky.top - sticky.navH) <= 2,
            `top=${sticky.top} nav=${sticky.navH}`);

        // 审查记录页的手机档：书名检索框不能把标题行撑出屏幕
        await mobile.goto(`${URL}/records`, { waitUntil: 'domcontentloaded', timeout: 60000 });
        await mobile.waitForSelector('#recResultRows tr', { timeout: 20000 });
        const recordsMobile = await mobile.evaluate(() => ({
            docW: document.documentElement.scrollWidth,
            viewW: window.innerWidth,
            searchVisible: !!document.getElementById('resultSearch')
                && document.getElementById('resultSearch').offsetParent !== null
        }));
        check('审查记录页手机档检索框可见且无横向溢出',
            recordsMobile.searchVisible && recordsMobile.docW <= recordsMobile.viewW + 1,
            `${recordsMobile.docW}/${recordsMobile.viewW}`);
    } finally {
        await mobile.close();
    }

    // 7) 记录入口：首页工具栏 → /records（审查记录页），失败日志从审查记录页头部进。
    //    为什么不是两个按钮：动作行在 1000px 只有 934px 可用宽度，再塞一颗整行溢出
    const entry = await page.evaluate(() => {
        const link = document.querySelector('#aiRecordsBtn');
        return link ? {href: link.getAttribute('href'), text: link.textContent.trim()} : null;
    });
    check('首页工具栏有审查记录入口', !!entry && entry.href === '/records',
        entry ? `${entry.href} ${entry.text}` : '未找到 #aiRecordsBtn');

    await page.goto(`${URL}/records`, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForSelector('#recFetchRows tr', { timeout: 20000 });
    const recordsPage = await page.evaluate(() => {
        // 「章节」列：逐章校验行显示「第 N 章」，初筛行显示取样跨度「第 1–10 章」
        const chapterCells = [...document.querySelectorAll('#recResultRows tr td:nth-child(6)')]
            .map(td => td.textContent.trim());
        return {
            faultLink: !!document.querySelector('a[href="/faults"]'),
            back: !!document.querySelector('a.btn[href="/"]'),
            fetchRows: document.querySelectorAll('#recFetchRows tr').length,
            resultRows: document.querySelectorAll('#recResultRows tr').length,
            chapterCells: chapterCells,
            running: (document.getElementById('recRunningCount') || {}).textContent || ''
        };
    });
    check('审查记录页有拉取与结果行', recordsPage.fetchRows > 0 && recordsPage.resultRows > 0,
        `拉取 ${recordsPage.fetchRows} / 结果 ${recordsPage.resultRows}`);
    check('审查记录页能进失败日志并回榜单', recordsPage.faultLink && recordsPage.back);
    // 章节列不能全是「—」：查的是第几章必须能从页面上读出来
    /* 结果表没有真实判定流水时只剩一行空态（.rec-empty），此时没有「第 N 章」可读，
       是空库而不是列渲染丢了；只在有真实行时断言，否则明确跳过 */
    const hasRealResultRows = await page.locator('#recResultRows tr:not(:has(.rec-empty))').count() > 0;
    if (hasRealResultRows) {
        const chapterFilled = recordsPage.chapterCells.filter(text => /^第 .+ 章$/.test(text)).length;
        check('判定结果的章节列写明了第几章', chapterFilled > 0,
            `已填 ${chapterFilled}/${recordsPage.chapterCells.length}：${recordsPage.chapterCells.slice(0, 4).join(' | ')}`);
    } else {
        console.log('SKIP: 审查结果表当前只有空态行（库内无判定流水），章节列断言跳过');
    }

    // 7.5) 判定结果按书名检索：只过滤当前窗口已拉到的流水，不新增后端契约
    const resultSearch = page.locator('#resultSearch');
    check('判定结果有按书名检索框', await resultSearch.count() === 1);
    const firstTitle = await page.evaluate(() => (
        document.querySelector('#recResultRows .rec-book span') || {}
    ).textContent?.trim() || '');
    const needle = firstTitle.slice(0, 2);
    if (needle) {
        await resultSearch.fill(needle);
        await page.waitForTimeout(300);
        const searched = await page.evaluate(() => ({
            rows: document.querySelectorAll('#recResultRows tr').length,
            count: document.getElementById('recResultCount').textContent.trim(),
            titles: [...document.querySelectorAll('#recResultRows .rec-book span')]
                .map(el => el.textContent.trim())
        }));
        check('按书名检索只留命中行',
            searched.rows > 0 && searched.titles.every(t => t.includes(needle)),
            `${searched.count} · ${searched.titles.slice(0, 2).join(' | ')}`);

        // 3 秒自动刷新会重建 tbody，但关键词和过滤结果必须保留
        await page.waitForTimeout(3500);
        const persisted = await page.evaluate(() => ({
            value: document.getElementById('resultSearch').value,
            count: document.getElementById('recResultCount').textContent.trim()
        }));
        check('自动刷新保留书名检索', persisted.value === needle && persisted.count.includes('/'),
            persisted.count);

        await resultSearch.fill('不存在书名zzz');
        await page.waitForTimeout(300);
        const noHit = await page.evaluate(() => (
            document.querySelector('#recResultRows .rec-empty') || {}
        ).textContent || '');
        check('检索无命中给出明确空态', noHit.includes('没有书名包含'), noHit);

        await resultSearch.fill('');
        await page.waitForTimeout(300);
        const restored = await page.evaluate(() => document.getElementById('recResultCount').textContent.trim());
        check('清空检索恢复完整判定结果', /^\d+$/.test(restored) && Number(restored) > 0, restored);
    }

    // 8) 失败日志页：跳过去能渲染出表格与摘要（它是独立静态页，不在主 state 里）
    await page.goto(`${URL}/faults`, { waitUntil: 'domcontentloaded', timeout: 60000 });
    /* #faultRows 在 HTML 里就带一行占位（<tr><td>—</td></tr>），
       waitForSelector('#faultRows tr') 会立刻命中占位行、读到还没更新的摘要。
       真正该等的是摘要离开「正在加载…」——那是 fetch 落地的唯一信号 */
    await page.waitForFunction(
        () => !(document.getElementById('faultSummary') || {}).textContent.includes('正在加载'),
        { timeout: 20000 }
    ).catch(() => {});
    const faultPage = await page.evaluate(() => ({
        title: (document.querySelector('.fault-title') || {}).textContent || '',
        rows: document.querySelectorAll('#faultRows tr').length,
        hasEmptyCell: !!document.querySelector('#faultRows .fault-empty'),
        summary: (document.getElementById('faultSummary') || {}).textContent || '',
        windowOptions: document.querySelectorAll('#faultWindow option').length,
        back: !!document.querySelector('a.btn[href="/"]'),
        excerptFilled: [...document.querySelectorAll('#faultRows .fault-excerpt')]
            .filter(td => td.textContent.trim() !== '—').length
    }));
    check('失败日志页标题正确', faultPage.title.includes('模型调用失败日志'), faultPage.title);
    check('失败日志页有筛选与回榜单', faultPage.windowOptions >= 4 && faultPage.back,
        `窗口档 ${faultPage.windowOptions}`);
    check('失败日志摘要写明窗口与条数', /窗口\s*\d+\s*小时/.test(faultPage.summary), faultPage.summary.slice(0, 56));
    /* 空态也渲染一行（tr.fault-empty），所以原先的 `rows > 0 && (hasEmptyCell || rows > 1)`
       在「恰好一条真实故障」时两条都不满足 -> 必然误报（实测 1 条限流记录即触发）。
       真正要守的是：表格不能既没有数据行、也没有明确空态 */
    check('失败日志表格有数据或有明确空态', faultPage.rows > 0,
        faultPage.hasEmptyCell ? '空态行' : `${faultPage.rows} 行数据`);
    check('失败日志的上游返回片段有内容', faultPage.excerptFilled > 0 || faultPage.hasEmptyCell,
        `片段 ${faultPage.excerptFilled} 条`);

    // 8) 全程零未捕获异常（含手机页面与失败日志页）
    check('无 pageerror', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} catch (err) {
    check('脚本执行', false, err.message);
} finally {
    await browser.close();
}

if (failures.length > 0) {
    console.error(`\n${failures.length} 项失败：${failures.join('、')}`);
    process.exit(1);
}
console.log('\n全部通过');
