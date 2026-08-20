// src/main/java/org/example/fqxs/constant/CategoryConstant.java
package org.example.fqxs.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CategoryConstant {

    // ==================== 男频阅读榜 ====================
    WESTERN_FANTASY(1141, "西方奇幻", 1, 2),   // ← 关键：ID为1141
    EASTERN_XIANXIA(1140, "东方仙侠", 1, 2),
    SCI_FI(8, "科幻末世", 1, 2),
    URBAN_XIANXIA(124, "都市修真", 1, 2),
    URBAN_HIGH_WU(1014, "都市高武", 1, 2),
    HISTORY_ANCIENT(273, "历史古代", 1, 2),
    URBAN_FARMING(263, "都市种田", 1, 2),
    TRADITIONAL_XUANHUAN(258, "传统玄幻", 1, 2),
    HISTORY_BRAIN(272, "历史脑洞", 1, 2),
    XUANHUAN_BRAIN(257, "玄幻脑洞", 1, 2),
    GAME_SPORTS(746, "游戏体育", 1, 2);

    private final Integer subcategoryId;
    private final String name;
    private final Integer rankType;    // 1=男频
    private final Integer categoryId;  // 2=阅读榜

    public static CategoryConstant fromName(String name) {
        for (CategoryConstant c : values()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    public static CategoryConstant fromId(Integer id) {
        for (CategoryConstant c : values()) {
            if (c.getSubcategoryId().equals(id)) {
                return c;
            }
        }
        return null;
    }
}