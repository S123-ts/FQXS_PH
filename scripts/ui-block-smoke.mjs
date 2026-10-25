/* ============================================================
   scripts/ui-block-smoke.mjs - 拉黑与批量操作的浏览器回归（24 项，含关注）
   背景：《技术交接》§7 长期挂着「拉黑只能人工回归」。拉黑是唯一会
   改动数据的行内操作，靠肉眼点一遍既不可重复、又有误伤真实库的风险。

   **写接口全部打桩**：block/unblock/watch/unwatch 的 POST 由 page.route 拦掉并回成功信封，
   真实库一行都不动。所以它验证的是前端行为（确认态、计数、串行次数、
   撤销回滚、关注即时生效），不是后端写入本身。

   运行：npm i（一次性）；node scripts/ui-block-smoke.mjs
   退出码：0 全绿；1 有断言失败
   ============================================================ */

import { pathToFileURL } from 'node:url';
import { statSync } from 'node:fs';

const URL_BASE = process.env.FQXS_URL || 'http://localhost:8080';

/* 登录门：未登录时页面全部被 302 到 /login，断言必然全红。
   凭据与 application.yml 的 app.auth 默认值一致，可用环境变量覆盖 */
const FQXS_USER = process.env.FQXS_USER || process.env.APP_LOGIN_USER || 'admin';
const FQXS_PASS = process.env.FQXS_PASS || process.env.APP_LOGIN_PASSWORD || 'admin';

async function loadChromium() {
    let specifier = process.env.PW_CORE || 'playwright-core';
    if (/^[a-zA-Z]:[\\/]/.test(specifier) && statSync(specifier).isDirectory()) {
        specifier = specifier.replace(/[\\/]+$/, '') + '/index.js';
    }
    const target = /^[a-zA-Z]:[\\/]/.test(specifier)
        ? pathToFileURL(specifier).href
        : specifier;
    const mod = await import(target);
    const api = mod.chromium ? mod : (mod.default || {});
    return api.chromium;
}

const failures = [];
const check = (name, ok, detail) => {
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail === undefined ? '' : ' → ' + detail}`);
    if (!ok) failures.push(name);
};

const chromium = await loadChromium();
const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || undefined,
    headless: true
});
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

// 与页面共用 cookie jar，登录一次即可
const loginRes = await page.request.post(`${URL_BASE}/api/auth/login`, {
    data: { username: FQXS_USER, password: FQXS_PASS }
});
if (!loginRes.ok()) {
    console.error(`[fail] 登录失败 HTTP ${loginRes.status()}：检查 FQXS_USER / FQXS_PASS 与服务端 app.auth 配置`);
    await browser.close();
    process.exit(1);
}

const pageErrors = [];
page.on('pageerror', e => pageErrors.push(String(e)));

/* allBooks 是纯读请求，不会被下面的 POST 桩拦下；记下 URL 用来核对筛选参数 */
const allBooksUrls = [];
page.on('request', req => {
    if (/\/allBooks/.test(req.url())) allBooksUrls.push(req.url());
});

/* 写接口打桩：只放行 GET，其余 POST 一律回成功信封，真实库不动 */
const calls = [];
await page.route('**/api/rank/**', async (route) => {
    const req = route.request();
    const url = req.url();
    if (req.method() === 'GET' || /write-token/.test(url)) {
        await route.continue();
        return;
    }
    calls.push(url.replace(URL_BASE, ''));
    await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'success', data: true })
    });
});

const waitRendered = () => page.waitForFunction(() => {
    const list = document.getElementById('bookList');
    return list && !list.querySelector('.loading') && list.querySelectorAll('.book-item').length > 0;
}, { timeout: 60000 });

try {
    await page.goto(URL_BASE, { waitUntil: 'domcontentloaded', timeout: 60000 });
    /* 默认作者筛选是「男作者」：批量与多选冒烟不依赖它，先切「全部」 */
    await page.selectOption('#filterAuthor', '');
    await page.waitForFunction(() => document.querySelectorAll('#bookList .book-item').length > 1,
        { timeout: 20000 });
    await waitRendered();

    /* ---------- 1. 批量条与行内方框 ---------- */
    /* 2026-10-24：批量条未选中时不再占位（桌面与手机一致），勾选后才出现 */
    const barVisible = await page.isVisible('#batchBar');
    const checks = await page.locator('#bookList .row-check').count();
    check('未选中时批量条不占位', !barVisible);
    check('每行都有勾选框', checks > 0 && checks === await page.locator('.book-item').count(),
        `${checks} 个`);

    /* ---------- 2. 勾一本 → 计数与按钮 ---------- */
    await page.locator('#bookList .row-check').first().check();
    const afterOne = await page.textContent('.batch-count');
    const blockBtnEnabled = await page.isEnabled('[data-action="batch-block"]');
    check('勾选一本后计数正确', (afterOne || '').includes('已选 1 本'), afterOne);
    check('勾选后批量按钮变为可用', blockBtnEnabled);

    /* ---------- 3. 全选 / 取消全选 ---------- */
    await page.locator('#batchAll').check();
    const afterAll = await page.textContent('.batch-count');
    const allChecked = await page.isChecked('#batchAll');
    check('全选本页计数等于行数', (afterAll || '').includes(`已选 ${checks} 本`), afterAll);
    check('全选后全选框为勾选态', allChecked);

    await page.locator('#batchAll').uncheck();
    const afterClear = await page.textContent('.batch-count');
    check('取消全选清空选择', (afterClear || '').includes('勾选左侧方框'), afterClear);

    /* ---------- 4. 批量拉黑：串行调用且逐本计数 ---------- */
    calls.length = 0;
    await page.locator('#bookList .row-check').nth(0).check();
    await page.locator('#bookList .row-check').nth(1).check();
    await page.click('[data-action="batch-block"]');
    await page.waitForFunction(() => {
        const t = document.querySelector('#toastHost .toast-body');
        return t && /已拉黑\s*2\s*本/.test(t.textContent || '');
    }, { timeout: 20000 }).catch(() => {});
    const batchToast = await page.textContent('#toastHost .toast-body').catch(() => '');
    check('批量拉黑两本后提示成功数', /已拉黑\s*2\s*本/.test(batchToast || ''), batchToast);
    check('批量拉黑串行发出两次写请求',
        calls.filter(u => /\/block\//.test(u)).length === 2, calls.join(' | '));
    const selectionAfter = await page.textContent('.batch-count').catch(() => '');
    check('批量完成后选择被清空', (selectionAfter || '').includes('勾选左侧方框'), selectionAfter);

    /* ---------- 4.5 关注：一次点击即发请求（不做二段确认） ---------- */
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 60000 });
    await waitRendered();
    const watchCount = await page.locator('#bookList .watch-btn').count();
    const watchableRows = await page.locator('#bookList .book-item[data-book-id]').count();
    check('每行都有「关注」按钮', watchCount > 0 && watchCount === watchableRows,
        `${watchCount}/${watchableRows}`);
    const watchBtn = page.locator('#bookList .watch-btn').first();
    const watchLabel = ((await watchBtn.textContent()) || '').trim();
    check('未关注的书显示「关注」', watchLabel === '关注', watchLabel);

    calls.length = 0;
    await watchBtn.click();
    await page.waitForTimeout(600);
    check('点一次关注立即发出写请求（无二段确认）',
        calls.filter(u => /\/watch\//.test(u)).length === 1, calls.join(' | '));

    /* 「只看关注」是纯读筛选：请求必须带 watchedOnly=true */
    await page.locator('#filterWatched').check();
    await page.waitForTimeout(1200);
    const watchedUrl = allBooksUrls[allBooksUrls.length - 1] || '';
    check('「只看关注」请求带 watchedOnly=true', /watchedOnly=true/.test(watchedUrl), watchedUrl);
    const summaryText = (await page.textContent('#chromeSummaryText')) || '';
    check('「只看关注」进入折叠摘要', summaryText.includes('只看关注'), summaryText);
    await page.locator('#filterWatched').uncheck();
    await page.waitForTimeout(800);

    /* ---------- 5. 拉黑二段确认 ---------- */
    /* 批量那轮会触发整页重绘：直接刷新一遍，拿到稳定的行节点再测确认态 */
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 60000 });
    await waitRendered();
    const blockBtn = page.locator('#bookList .block-btn').first();
    await blockBtn.scrollIntoViewIfNeeded();
    calls.length = 0;
    await blockBtn.click();
    const confirmText = await blockBtn.textContent();
    const confirmClass = await blockBtn.getAttribute('class');
    await page.waitForTimeout(300);
    check('第一次点击只进入确认态', (confirmText || '').includes('确认拉黑'), confirmText);
    check('确认态有 confirm 样式', (confirmClass || '').includes('confirm'), confirmClass);
    check('确认态不发写请求', calls.length === 0, calls.join(' | '));

    /* 点别处应放弃确认 */
    await page.click('#totalCount');
    const resetText = await blockBtn.textContent();
    check('点别处复位为「拉黑」', (resetText || '').trim() === '拉黑', resetText);

    /* 连点两次才真正执行 */
    await blockBtn.click();
    await blockBtn.click();
    await page.waitForTimeout(500);
    const blockCalls = calls.filter(u => /\/block\//.test(u));
    check('连点两次才发出一次拉黑请求', blockCalls.length === 1, calls.join(' | '));

    /* ---------- 6. 撤销 toast ---------- */
    const toastText = await page.textContent('#toastHost .toast-body').catch(() => '');
    const undoVisible = await page.isVisible('#toastHost .toast-action');
    check('拉黑后提示带书名并出现撤销按钮',
        (toastText || '').includes('已拉黑') && undoVisible, toastText);

    calls.length = 0;
    await page.click('#toastHost .toast-action');
    await page.waitForTimeout(500);
    check('点撤销发出一次恢复请求',
        calls.filter(u => /\/unblock\//.test(u)).length === 1, calls.join(' | '));

    /* ---------- 7. 分类视图同样支持多选（2026-10-22 起「校验选中」是全局动作） ---------- */
    const navBtn = page.locator('.nav-btn[data-code]:not([data-code="ALL"])').first();
    if (await navBtn.count()) {
        const code = await navBtn.getAttribute('data-code');
        await navBtn.click();
        await page.waitForFunction((target) => {
            const active = document.querySelector('.nav-btn.active');
            return active && active.getAttribute('data-code') === target;
        }, code, { timeout: 20000 });
        await waitRendered();
        const catChecks = await page.locator('#bookList .row-check').count();
        const catBarVisible = await page.isVisible('#batchBar');
        check('分类视图有勾选框且未选中时批量条不占位', catChecks > 0 && !catBarVisible,
            `分类=${code} / ${catChecks} 个 / 批量条可见=${catBarVisible}`);

        // 「校验选中」按钮随选中数启用：未选禁用，勾一本后启用并显示计数
        const disabledBefore = await page.locator('#aiPageBtn').isDisabled();
        await page.locator('#bookList .row-check').first().check();
        await page.waitForTimeout(200);
        check('分类视图勾选后批量条出现', await page.isVisible('#batchBar'));
        const disabledAfter = await page.locator('#aiPageBtn').isDisabled();
        const countText = (await page.locator('#aiPageBtn .btn-count').textContent()) || '';
        check('勾选后「校验选中」才可用并显示数量',
            disabledBefore && !disabledAfter && countText.trim() === '1',
            `前=${disabledBefore} 后=${disabledAfter} 计数=${countText.trim()}`);
    }

    check('无 pageerror', pageErrors.length === 0, pageErrors.join(' | ').slice(0, 200));
} catch (err) {
    check('脚本执行', false, err.message);
} finally {
    await browser.close();
    if (failures.length) {
        console.error(`\n${failures.length} 项失败：${failures.join('、')}`);
        process.exit(1);
    }
    console.log('\n全部通过');
}
