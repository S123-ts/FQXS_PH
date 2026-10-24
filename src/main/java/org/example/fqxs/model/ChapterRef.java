package org.example.fqxs.model;

/**
 * 目录里的一个章节：Legado 书源需要标题与分卷，AI 取样只需要 itemId。
 *
 * <p>{@code locked} 由上游的 {@code isChapterLock} 与 {@code needPay} 合成——
 * 锁章仍会出现在目录里，只是正文端点会明确报错，不伪装成空章。</p>
 *
 * @param itemId     章节 ID
 * @param title      章节标题（上游目录里的标题不做 PUA 混淆，不含私用区字符）
 * @param volumeName 所属卷名，可能为空
 * @param locked     是否锁章/付费章
 */
public record ChapterRef(String itemId, String title, String volumeName, boolean locked) {
}
