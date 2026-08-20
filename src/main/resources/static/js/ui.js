/* ============================================================
   js/ui.js - UI 渲染函数
   ============================================================ */

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function renderNav() {
    const nav = document.getElementById('navInner');
    nav.innerHTML = CATEGORIES.map(cat =>
        `<button class="nav-btn ${cat.code === currentCategory ? 'active' : ''}"
                 data-code="${cat.code}"
                 onclick="switchCategory('${cat.code}')">
            ${cat.name}
        </button>`
    ).join('');
}

function renderBooks(data) {
    const listEl = document.getElementById('bookList');
    let novels = data.data;
    if (!Array.isArray(novels)) {
        novels = data.data || [];
    }
    if (!Array.isArray(novels)) {
        novels = [];
    }

    document.getElementById('totalCount').textContent = novels.length;

    const cat = CATEGORIES.find(c => c.code === currentCategory);
    if (cat) {
        document.getElementById('currentCategory').textContent = cat.name;
    }

    if (novels.length === 0) {
        listEl.innerHTML = `
            <div class="empty">
                <span class="icon">📭</span>
                <p>暂无数据</p>
            </div>
        `;
        return;
    }

    listEl.innerHTML = novels.map((book, index) => {
        const rank = index + 1;
        let rankClass = 'rank';
        if (rank === 1) rankClass += ' top1';
        else if (rank === 2) rankClass += ' top2';
        else if (rank === 3) rankClass += ' top3';

        const statusClass = book.status === '已完结' ? 'finished' : '';

        return `
            <div class="book-item">
                <div class="${rankClass}">${rank}</div>
                <div class="content">
                    <div class="title font-DNMrHsV173Pd4pgy">
                        <a href="https://fanqienovel.com/page/${book.bookId}" target="_blank" style="color: #1a1a2e; text-decoration: none;">
                            ${escapeHtml(book.title || '未知书名')}
                        </a>
                        <span class="status ${statusClass}">${escapeHtml(book.status || '连载中')}</span>
                        ${currentCategory === 'ALL' ? `<button class="block-btn" onclick="blockBook('${book.bookId}', this)">拉黑</button>` : ''}
                    </div>
                    <div class="info-row">
                        <span class="author font-DNMrHsV173Pd4pgy">${escapeHtml(book.author || '未知作者')}</span>
                        <span class="divider">|</span>
                        <span>📖 ${escapeHtml(book.readCount || '0')} 人在读</span>
                        <span class="divider">|</span>
                        <span>📝 ${escapeHtml(book.wordCount || '')}</span>
                        <span class="divider">|</span>
                        <span>🕐 ${escapeHtml(book.updateTime || '')}</span>
                        <span class="divider">|</span>
                        <span>🏷️ ${escapeHtml(book.categoryName || '')}</span>
                    </div>
                    <div class="desc font-DNMrHsV173Pd4pgy">
                        ${escapeHtml(book.description || '暂无简介')}
                    </div>
                    <div class="meta">
                        <span>📌 最近更新：${escapeHtml(book.lastChapter || '')}</span>
                    </div>
                </div>
            </div>
        `;
    }).join('');
}