package org.example.fqxs.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 排行榜分类总目录：男频 19 个 + 女频 18 个，顺序即上游榜单顺序（meta 接口按此输出）。
 * 定位一个榜需要 (gender, subcategoryId) 二元组：gender 1=男频 0=女频；
 * 男女频共用部分 category_id（科幻末世=8、游戏体育=746、悬疑脑洞=539），
 * 所以存储与接口一律用全局唯一的 code（M_/F_）区分男女。
 */
@Getter
@AllArgsConstructor
public enum CategoryCatalog {

    // ---- 男频 gender=1 ----
    M_WESTERN_FANTASY(1, 1141, "西方奇幻"),
    M_EASTERN_XIANXIA(1, 1140, "东方仙侠"),
    M_SCI_FI(1, 8, "科幻末世"),
    M_URBAN_DAILY(1, 261, "都市日常"),
    M_URBAN_CULTIVATION(1, 124, "都市修真"),
    M_URBAN_HIGH_WU(1, 1014, "都市高武"),
    M_HISTORY_ANCIENT(1, 273, "历史古代"),
    M_WAR_GOD(1, 27, "战神赘婿"),
    M_URBAN_FARMING(1, 263, "都市种田"),
    M_TRADITIONAL_XUANHUAN(1, 258, "传统玄幻"),
    M_HISTORY_BRAIN(1, 272, "历史脑洞"),
    M_SUSPENSE_BRAIN(1, 539, "悬疑脑洞"),
    M_URBAN_BRAIN(1, 262, "都市脑洞"),
    M_XUANHUAN_BRAIN(1, 257, "玄幻脑洞"),
    M_SUSPENSE_SUPERNATURAL(1, 751, "悬疑灵异"),
    M_WAR_SPY(1, 504, "抗战谍战"),
    M_GAME_SPORTS(1, 746, "游戏体育"),
    M_ANIME_DERIVED(1, 718, "动漫衍生"),
    M_MALE_DERIVED(1, 1016, "男频衍生"),

    // ---- 女频 gender=0 ----
    F_ANCIENT_ROMANCE(0, 1139, "古风世情"),
    F_SCI_FI(0, 8, "科幻末世"),
    F_GAME_SPORTS(0, 746, "游戏体育"),
    F_FEMALE_DERIVED(0, 1015, "女频衍生"),
    F_XUANHUAN_ROMANCE(0, 248, "玄幻言情"),
    F_FARMING(0, 23, "种田"),
    F_ERA(0, 79, "年代"),
    F_MODERN_ROMANCE_BRAIN(0, 267, "现言脑洞"),
    F_PALACE_INNER(0, 246, "宫斗宅斗"),
    F_SUSPENSE_BRAIN(0, 539, "悬疑脑洞"),
    F_ANCIENT_ROMANCE_BRAIN(0, 253, "古言脑洞"),
    F_QUICK_TRANS(0, 24, "快穿"),
    F_YOUTH_SWEET(0, 749, "青春甜宠"),
    F_STAR_SHINING(0, 745, "星光璀璨"),
    F_FEMALE_SUSPENSE(0, 747, "女频悬疑"),
    F_CAREER_MARRIAGE(0, 750, "职场婚恋"),
    F_RICH_CEO(0, 748, "豪门总裁"),
    F_REPUBLIC_ROMANCE(0, 1017, "民国言情");

    private final int gender;
    private final int subcategoryId;
    private final String name;

    /** 枚举名即全局唯一 code（M_/F_），落库与接口统一用它。 */
    public String getCode() {
        return name();
    }

    private static final Map<String, CategoryCatalog> BY_CODE = new HashMap<>();
    private static final Map<Integer, CategoryCatalog> BY_GENDER_SUBCATEGORY = new HashMap<>();

    static {
        for (CategoryCatalog category : values()) {
            BY_CODE.put(category.getCode(), category);
            BY_GENDER_SUBCATEGORY.put(key(category.gender, category.subcategoryId), category);
        }
    }

    public static CategoryCatalog fromCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** 男女频共用 subcategoryId，必须带 gender 才能唯一定位。 */
    public static CategoryCatalog fromSubcategoryId(int gender, int subcategoryId) {
        return BY_GENDER_SUBCATEGORY.get(key(gender, subcategoryId));
    }

    private static Integer key(int gender, int subcategoryId) {
        return gender * 100_000 + subcategoryId;
    }
}
