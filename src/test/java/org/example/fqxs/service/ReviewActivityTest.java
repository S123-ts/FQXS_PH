package org.example.fqxs.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 在飞审查登记：审查记录页的「正在 AI 审查」全靠它，漏掉 end 也不能无限堆积。 */
class ReviewActivityTest {

    @Test
    void beginAndEndTrackRunningItems() {
        ReviewActivity activity = new ReviewActivity();
        String id = activity.begin(ReviewActivity.Kind.VERIFY, "book-1", "glm-4-flash", "推进一章");

        assertThat(activity.size()).isEqualTo(1);
        ReviewActivity.Running item = activity.snapshot().get(0);
        assertThat(item.id()).isEqualTo(id);
        assertThat(item.bookId()).isEqualTo("book-1");
        assertThat(item.engine()).isEqualTo("glm-4-flash");
        assertThat(item.kindLabel()).isEqualTo("逐章校验");
        assertThat(item.detail()).isEqualTo("推进一章");

        activity.end(id);
        assertThat(activity.size()).isZero();
    }

    /** 批量初筛没有单本书，bookId 允许为空——页面按 detail 显示「一批 N 本」。 */
    @Test
    void sweepMayHaveNoBookId() {
        ReviewActivity activity = new ReviewActivity();
        activity.begin(ReviewActivity.Kind.SWEEP, null, "glm-4-flash", "一批 5 本");

        assertThat(activity.snapshot().get(0).bookId()).isNull();
        assertThat(activity.snapshot().get(0).kindLabel()).isEqualTo("批量初筛");
    }

    /** end 要幂等：finally 里重复调用、传 null 都不该抛。 */
    @Test
    void endIsIdempotent() {
        ReviewActivity activity = new ReviewActivity();
        String id = activity.begin(ReviewActivity.Kind.DETECT, "book-2", "glm-4-flash", "整本初筛");

        activity.end(id);
        assertThatCode(() -> activity.end(id)).doesNotThrowAnyException();
        assertThatCode(() -> activity.end(null)).doesNotThrowAnyException();
    }

    /** 容量兜底：某条 end 漏掉时丢最早的，避免无界增长。 */
    @Test
    void capacityIsBounded() {
        ReviewActivity activity = new ReviewActivity();
        for (int i = 0; i < ReviewActivity.MAX_RUNNING + 20; i++) {
            activity.begin(ReviewActivity.Kind.DETECT, "book-" + i, "glm-4-flash", "整本初筛");
        }
        assertThat(activity.size()).isEqualTo(ReviewActivity.MAX_RUNNING);
    }
}
