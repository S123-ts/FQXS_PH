/* ============================================================
   js/app.js - 主逻辑（强制全选所有分类）
   ============================================================ */

document.addEventListener('DOMContentLoaded', function() {
    const select = document.getElementById('pageSizeSelect');
    if (select) {
        currentPageSize = parseInt(select.value, 10);
    }
    renderNav();
    populateFilterCategories();
    document.getElementById('filterBar').style.display = 'flex';
    loadData();
});

// ---------- 填充分类下拉多选 ----------
function populateFilterCategories() {
    const menu = document.getElementById('dropdownMenu');
    if (!menu) return;
    menu.innerHTML = '';

    // ★★★ 关键修复：强制初始化为所有分类名称 ★★★
    // 无论之前是什么值，都重置为所有分类
    selectedCategories = CATEGORIES
        .filter(c => c.code !== 'ALL')
        .map(c => c.name);

    // 全选选项
    const allItem = document.createElement('div');
    allItem.className = 'dropdown-item check-all';
    allItem.innerHTML = `
        <input type="checkbox" id="checkAll" checked onchange="toggleAllCategories(this.checked)">
        <label for="checkAll">全选</label>
    `;
    menu.appendChild(allItem);

    const divider = document.createElement('hr');
    divider.style.cssText = 'border: none; border-top: 1px solid #eee; margin: 4px 0;';
    menu.appendChild(divider);

    // 各分类选项
    CATEGORIES.filter(c => c.code !== 'ALL').forEach(cat => {
        const item = document.createElement('div');
        item.className = 'dropdown-item';
        // 由于上面强制 selectedCategories 包含所有分类，这里一定为 true
        const checked = selectedCategories.includes(cat.name);
        item.innerHTML = `
            <input type="checkbox" id="cat_${cat.code}" ${checked ? 'checked' : ''}
                   onchange="onCategoryToggle('${cat.name}', this.checked)">
            <label for="cat_${cat.code}">${cat.name}</label>
        `;
        menu.appendChild(item);
    });

    // 更新界面
    updateDropdownLabel();
    updateCheckAllState();
}

// ---------- 更新全选复选框状态 ----------
function updateCheckAllState() {
    const allCheckbox = document.getElementById('checkAll');
    if (!allCheckbox) return;
    const total = CATEGORIES.filter(c => c.code !== 'ALL').length;
    const selectedCount = selectedCategories.length;
    allCheckbox.checked = (selectedCount === total);
    allCheckbox.indeterminate = (selectedCount > 0 && selectedCount < total);
}

// ---------- 切换下拉菜单 ----------
function toggleDropdown() {
    const menu = document.getElementById('dropdownMenu');
    if (!menu) return;
    isDropdownOpen = !isDropdownOpen;
    menu.classList.toggle('show', isDropdownOpen);
}

function closeDropdown() {
    const menu = document.getElementById('dropdownMenu');
    if (menu) {
        menu.classList.remove('show');
        isDropdownOpen = false;
    }
}

document.addEventListener('click', function(e) {
    const container = document.getElementById('dropdownMulti');
    if (container && !container.contains(e.target)) {
        closeDropdown();
    }
});

// ---------- 分类复选框切换 ----------
function onCategoryToggle(name, checked) {
    if (checked) {
        if (!selectedCategories.includes(name)) {
            selectedCategories.push(name);
        }
    } else {
        selectedCategories = selectedCategories.filter(c => c !== name);
    }
    updateDropdownLabel();
    updateCheckAllState();
    onFilterChange();
}

// ---------- 全选/取消全选 ----------
function toggleAllCategories(checked) {
    const items = document.querySelectorAll('.dropdown-item input[type="checkbox"]');
    items.forEach(cb => {
        if (cb.id !== 'checkAll') {
            cb.checked = checked;
            const label = cb.nextElementSibling;
            if (label) {
                const name = label.textContent;
                if (checked) {
                    if (!selectedCategories.includes(name)) {
                        selectedCategories.push(name);
                    }
                } else {
                    selectedCategories = selectedCategories.filter(c => c !== name);
                }
            }
        }
    });
    const allCheckbox = document.getElementById('checkAll');
    if (allCheckbox) {
        allCheckbox.checked = checked;
        allCheckbox.indeterminate = false;
    }
    updateDropdownLabel();
    onFilterChange();
}

// ---------- 更新下拉按钮文字 ----------
function updateDropdownLabel() {
    const label = document.getElementById('dropdownLabel');
    if (!label) return;
    const total = CATEGORIES.filter(c => c.code !== 'ALL').length;
    if (selectedCategories.length === 0) {
        label.textContent = '未选择';
    } else if (selectedCategories.length === total) {
        label.textContent = '全部';
    } else {
        label.textContent = '已选 ' + selectedCategories.length + ' 个';
    }
}

function getSelectedCategories() {
    return selectedCategories;
}

function onPageSizeChange() {
    const select = document.getElementById('pageSizeSelect');
    currentPageSize = parseInt(select.value, 10);
    loadData();
}

function onFilterChange() {
    if (currentCategory === 'ALL') {
        loadData();
    }
}

function switchCategory(code) {
    currentCategory = code;
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.code === code);
    });
    const cat = CATEGORIES.find(c => c.code === code);
    if (cat) {
        document.getElementById('currentCategory').textContent = cat.name;
    }
    const filterBar = document.getElementById('filterBar');
    filterBar.style.display = code === 'ALL' ? 'flex' : 'none';
    loadData();
}

function loadData() {
    const listEl = document.getElementById('bookList');
    listEl.innerHTML = `
        <div class="loading">
            <div class="spinner"></div>
            <p>加载中...</p>
        </div>
    `;

    let url;
    if (currentCategory === 'ALL') {
        const selected = getSelectedCategories();
        const categoryParam = selected.length > 0 ? selected.join(',') : '';
        const wordRange = document.getElementById('filterWord').value || '';
        const status = document.getElementById('filterStatus').value || '';
        url = `${API_BASE}/allBooks?category=${encodeURIComponent(categoryParam)}&wordRange=${encodeURIComponent(wordRange)}&status=${encodeURIComponent(status)}`;
    } else {
        url = `${API_BASE}?category=${currentCategory}&page=1&size=${currentPageSize}`;
    }

    fetchRankList(url)
        .then(data => {
            if (data.code !== 0) throw new Error(data.message || '未知错误');
            renderBooks(data);
        })
        .catch(err => {
            console.error('加载失败:', err);
            listEl.innerHTML = `
                <div class="empty">
                    <span class="icon">😅</span>
                    <p>加载失败，请稍后重试</p>
                    <p style="font-size:13px;color:#ccc;">${err.message}</p>
                </div>
            `;
        });
}

// ---------- 拉黑书籍（无确认框） ----------
function blockBook(bookId, btnElement) {
    btnElement.disabled = true;
    btnElement.textContent = '拉黑中...';

    blockBookApi(bookId)
        .then(data => {
            if (data.code !== 0) throw new Error(data.message || '拉黑失败');
            loadData();
        })
        .catch(err => {
            console.error('拉黑失败:', err);
            alert('拉黑失败: ' + err.message);
            btnElement.disabled = false;
            btnElement.textContent = '拉黑';
        });
}

// ---------- 一键获取全部 ----------
function fetchAllData() {
    const btn = document.querySelector('.fetch-all-btn');
    if (!btn) return;
    btn.disabled = true;
    btn.textContent = '⏳ 获取中...';

    const size = currentPageSize;
    fetchAllDataApi(size)
        .then(data => {
            if (data.code !== 0) throw new Error(data.message || '未知错误');
            loadData();
        })
        .catch(err => {
            console.error('一键获取失败:', err);
            alert('❌ 一键获取失败: ' + err.message);
        })
        .finally(() => {
            btn.disabled = false;
            btn.textContent = '📥 一键获取全部';
        });
}