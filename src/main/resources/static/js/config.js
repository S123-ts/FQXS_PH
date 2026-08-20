// src/main/resources/static/js/config.js
/* ============================================================
   js/config.js - 配置和常量
   ============================================================ */

const API_BASE = '/api/rank';
const CATEGORIES = [
    { code: 'ALL', id: null, name: '全部' },
    { code: 'WESTERN_FANTASY', id: 1141, name: '西方奇幻' },
    { code: 'EASTERN_XIANXIA', id: 1140, name: '东方仙侠' },
    { code: 'SCI_FI', id: 8, name: '科幻末世' },
    { code: 'URBAN_HIGH_WU', id: 1014, name: '都市高武' },
    { code: 'HISTORY_ANCIENT', id: 273, name: '历史古代' },
    { code: 'URBAN_FARMING', id: 263, name: '都市种田' },
    { code: 'TRADITIONAL_XUANHUAN', id: 258, name: '传统玄幻' },
    { code: 'HISTORY_BRAIN', id: 272, name: '历史脑洞' },
    { code: 'XUANHUAN_BRAIN', id: 257, name: '玄幻脑洞' },
    { code: 'GAME_SPORTS', id: 746, name: '游戏体育' }
];

let currentCategory = 'ALL';
let currentPageSize = 30;
let selectedCategories = [];
let isDropdownOpen = false;