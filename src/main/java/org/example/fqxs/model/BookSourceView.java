package org.example.fqxs.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 书源（Legado「阅读」）搜索与详情共用的书籍视图。
 *
 * <p>字段名按 Legado 书源规则里的键命名（{@code name / author / cover / intro / kind / wordCount}），
 * 消费方规则可以退化成直接取字段。搜索列表不带 {@code status / lastChapter / tocUrl}，
 * 用 {@link JsonInclude} 把这些 null 键省掉。</p>
 *
 * @param bookId      书籍 ID
 * @param name        书名（PUA 已还原）
 * @param author      作者
 * @param cover       封面直链（签名 CDN，约 7 天后过期）
 * @param intro       简介
 * @param kind        分类名
 * @param wordCount   字数展示串（如「196.4万字」）
 * @param status      连载状态，仅详情
 * @param lastChapter 最新章节名，仅详情
 * @param tocUrl      目录端点完整地址，仅详情
 * @param bookUrl     详情端点完整地址；搜索与详情都返回，书源规则直接取它
 *                    （配置书源令牌时由服务端拼好 token，规则不用自己拼）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookSourceView(String bookId, String name, String author, String cover, String intro,
                             String kind, String wordCount, String status, String lastChapter,
                             String tocUrl, String bookUrl) {
}
