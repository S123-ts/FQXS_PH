package org.example.fqxs.web.dto;

import java.util.List;

/** 页面引导所需的榜单类型、当前生效类型与全目录（含 displayed 标记）。 */
public record MetaView(List<RankTypeView> rankTypes, String activeRankType, List<CategoryView> categories) {

    public record RankTypeView(String code, String name, int rankMold) {
    }

    public record CategoryView(String code, String name, int gender, int subcategoryId, boolean displayed) {
    }
}
