package org.example.fqxs.web.dto;

import java.util.List;

/**
 * 页面引导所需的榜单类型、拉取频道与全目录（含 displayed 标记）。
 *
 * @param rankTypes      可选榜单类型（阅读榜 / 新书榜）
 * @param activeRankType 当前生效的榜单类型
 * @param fetchGender    当前拉取频道：all / male / female
 * @param fetchGenders   拉取频道选项（页面按它渲染分段单选，文案不在前端写死）
 * @param categories     全 37 项分类，男频块在前、女频块在后
 */
public record MetaView(List<RankTypeView> rankTypes, String activeRankType,
                       String fetchGender, List<FetchGenderView> fetchGenders,
                       List<CategoryView> categories) {

    public record RankTypeView(String code, String name, int rankMold) {
    }

    /** 拉取频道选项；hint 说明这一档对拉取的实际影响，页面直接显示，避免用户猜。 */
    public record FetchGenderView(String code, String name, String hint) {
    }

    public record CategoryView(String code, String name, int gender, int subcategoryId, boolean displayed) {
    }
}
