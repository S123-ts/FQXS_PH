// @ts-check
/* ============================================================
   js/login.js - 登录页交互
   只有三件事：已登录就别显示表单、提交账号口令、口令可见性切换。
   与主界面零共享（不引 state/api），登录页必须在「主界面脚本加载失败」时也能用。
   ============================================================ */

const form = /** @type {HTMLFormElement} */ (document.getElementById('loginForm'));
const usernameInput = /** @type {HTMLInputElement} */ (document.getElementById('username'));
const passwordInput = /** @type {HTMLInputElement} */ (document.getElementById('password'));
const submitBtn = /** @type {HTMLButtonElement} */ (document.getElementById('loginSubmit'));
const errorBox = /** @type {HTMLElement} */ (document.getElementById('loginError'));
const eyeBtn = /** @type {HTMLButtonElement} */ (document.getElementById('togglePassword'));

let busy = false;

/**
 * 登录后跳回的地址：只接受本站绝对路径。
 *
 * `//evil.com` 是协议相对 URL，`location.replace('//evil.com')` 会跳到外站——
 * 这是开放重定向，必须挡。`/\evil.com` 在部分浏览器里等价，一并挡掉。
 * @param {string|null} raw
 * @returns {string}
 */
function safeRedirect(raw) {
    if (!raw || !raw.startsWith('/')) return '/';
    if (raw.startsWith('//') || raw.startsWith('/\\')) return '/';
    return raw;
}

const redirectTo = safeRedirect(new URLSearchParams(location.search).get('redirect'));

/** @param {string} message */
function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
}

function clearError() {
    errorBox.textContent = '';
    errorBox.hidden = true;
}

/** @param {boolean} value */
function setBusy(value) {
    busy = value;
    submitBtn.disabled = value;
    submitBtn.textContent = value ? '登录中…' : '登录';
}

/* ---------- 已登录：直接回目标页，不要让人再输一次 ---------- */

async function redirectIfAuthenticated() {
    try {
        const res = await fetch('/api/auth/me', {
            headers: { 'Accept': 'application/json;charset=UTF-8' },
            cache: 'no-store'
        });
        if (!res.ok) return;
        const body = await res.json();
        if (body && body.code === 0 && body.data && body.data.authenticated) {
            location.replace(redirectTo);
        }
    } catch (e) {
        // 网络异常就留在登录页：这里报错只会让人更困惑，真正的失败在提交时提示
    }
}

/* ---------- 提交 ---------- */

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;
    if (!username || !password) {
        showError('请输入账号与口令');
        (username ? passwordInput : usernameInput).focus();
        return;
    }

    clearError();
    setBusy(true);
    try {
        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json;charset=UTF-8',
                'Accept': 'application/json;charset=UTF-8'
            },
            body: JSON.stringify({ username, password })
        });

        let body = null;
        try {
            body = await res.json();
        } catch (e) {
            body = null; // 非 JSON（反代错误页等）走状态码兜底
        }

        if (res.ok && body && body.code === 0) {
            // 整页跳转而不是前端路由：主界面要在「已登录」状态下重新引导一次
            location.replace(redirectTo);
            return;
        }

        // 后端已经把 401/429/403 的文案写成可直接展示的中文
        showError((body && body.message) || `登录失败（HTTP ${res.status}）`);
        passwordInput.value = '';
        passwordInput.focus();
    } catch (e) {
        showError('网络异常，请检查服务是否在运行后重试');
    } finally {
        setBusy(false);
    }
});

/* ---------- 口令可见性 ---------- */

eyeBtn.addEventListener('click', () => {
    const showing = passwordInput.type === 'text';
    passwordInput.type = showing ? 'password' : 'text';
    eyeBtn.textContent = showing ? '显示' : '隐藏';
    eyeBtn.setAttribute('aria-label', showing ? '显示口令' : '隐藏口令');
    eyeBtn.setAttribute('aria-pressed', String(!showing));
    passwordInput.focus();
});

/* 输入时清掉上一次的失败提示，避免「已经改了还在报旧错」 */
[usernameInput, passwordInput].forEach((el) => {
    el.addEventListener('input', () => {
        if (!errorBox.hidden) clearError();
    });
});

redirectIfAuthenticated();
