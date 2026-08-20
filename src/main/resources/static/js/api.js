/* ============================================================
   js/api.js - API 请求
   ============================================================ */

function fetchRankList(url) {
    return fetch(url, {
        headers: { 'Accept': 'application/json;charset=UTF-8' }
    })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        });
}

function blockBookApi(bookId) {
    return fetch(`${API_BASE}/block/${bookId}`, {
        method: 'POST',
        headers: { 'Accept': 'application/json;charset=UTF-8' }
    })
        .then(res => {
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            return res.json();
        });
}

function fetchAllDataApi(size) {
    return fetchRankList(`${API_BASE}/fetchAll?size=${size}`);
}