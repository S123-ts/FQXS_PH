// src/main/java/org/example/fqxs/model/NovelInfo.java
package org.example.fqxs.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NovelInfo {
    private String rank;
    private String bookId;
    private String title;
    private String author;
    private String description;
    private String status;
    private String readCount;
    private Long readCountRaw;
    private String lastChapter;
    private String updateTime;
    private String thumbUri;
    private String categoryName;
    private String wordCount;
}