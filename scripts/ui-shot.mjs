/* 截图脚本：按给定宽度拍首屏与分类页，用于人工比对 UI 改动
   用法：node scripts/ui-shot.mjs [输出目录]
   依赖同 ui-smoke.mjs（playwright-core） */
import { pathToFileURL } from 'node:url';
import { statSync, mkdirSync } from 'node:fs';

const URL = process.env.FQXS_URL || 'http://localhost:8080';
const OUT = process.argv[2] || 'shots';
// DARK=1 时用 prefers-color-scheme: dark 打开，用于验暗色令牌
const SCHEME = process.env.DARK === '1' ? 'dark' : 'light';
mkdirSync(OUT, { recursive: true });

async function loadChromium() {
    let specifier = process.env.PW_CORE || 'playwright-core';
    if (/^[a-zA-Z]:[\\/]/.test(specifier) && statSync(specifier).isDirectory()) {
        specifier = specifier.replace(/[\\/]+$/, '') + '/index.js';
    }
    const target = /^[a-zA-Z]:[\\/]/.test(specifier) ? pathToFileURL(specifier).href : specifier;
    const mod = await import(target);
    const api = mod.chromium ? mod : (mod.default || {});
    return api.chromium;
}

const chromium = await loadChromium();
const browser = await chromium.launch({
    executablePath: process.env.CHROME_PATH || undefined,
    headless: true
});

const sizes = [[1440, 1000], [1200, 900], [1000, 900], [768, 900], [420, 900]];
/* 登录门：每个 page 是独立上下文，所以每档宽度都要先登录一次 */
const FQXS_USER = process.env.FQXS_USER || '123';
const FQXS_PASS = process.env.FQXS_PASS || 'admin';
for (const [w, h] of sizes) {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1, colorScheme: SCHEME });
    const loginRes = await page.request.post(`${URL}/api/auth/login`, {
        data: { username: FQXS_USER, password: FQXS_PASS }
    });
    if (!loginRes.ok()) {
        console.error(`[fail] 登录失败 HTTP ${loginRes.status()}：检查 FQXS_USER / FQXS_PASS`);
        await browser.close();
        process.exit(1);
    }
    await page.goto(URL, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForFunction(() => {
        const l = document.getElementById('bookList');
        return l && !l.querySelector('.loading');
    }, { timeout: 60000 }).catch(() => {});
    await page.waitForTimeout(600);
    await page.screenshot({ path: `${OUT}/top-${w}.png` });
    // 滚到卡片区，看单行卡片密度
    await page.evaluate(() => window.scrollTo(0, 520));
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${OUT}/list-${w}.png` });
    await page.close();
    console.log(`shot ${w}x${h}`);
}
await browser.close();
