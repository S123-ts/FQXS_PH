package org.example.fqxs.model;

/**
 * 一次章节抓取的结果。
 *
 * @param itemId 章节 ID
 * @param title  章节标题
 * @param text   已还原为明文、去标签后的正文
 * @param chars  正文字数（按字符计，含标点）
 */
public record ChapterSample(String itemId, String title, String text, int chars) {
}
