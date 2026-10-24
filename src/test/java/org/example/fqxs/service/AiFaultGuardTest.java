package org.example.fqxs.service;

import org.example.fqxs.config.AiDetectConfig;
import org.example.fqxs.entity.AiEngine;
import org.example.fqxs.entity.AiEngineFault;
import org.example.fqxs.exception.CrawlException;
import org.example.fqxs.repository.AiEngineFaultRepository;
import org.example.fqxs.repository.AiEngineRepository;
import org.example.fqxs.util.DateUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败留证与自动停用的契约：证据要进库、连续失败要停用两天、成功要清零、留证本身不能拖垮判定。
 */
@ExtendWith(MockitoExtension.class)
class AiFaultGuardTest {

    @Mock
    private AiEngineFaultRepository faultRepository;

    @Mock
    private AiEngineRepository engineRepository;

    private AiDetectConfig config;

    private AiFaultGuard guard;

    @BeforeEach
    void setUp() {
        config = new AiDetectConfig();
        guard = new AiFaultGuard(faultRepository, engineRepository, config);
        lenient().when(faultRepository.save(any(AiEngineFault.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(engineRepository.save(any(AiEngine.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AiEngine engine(int failures) {
        return AiEngine.builder().id("glm-4-flash").name("GLM").baseUrl("https://x/chat/completions")
                .model("glm-4-flash").protocol("openai_chat").builtin(true).enabled(true)
                .autoBan(true).priority(0).consecutiveFailures(failures).build();
    }

    /** reason 列 NOT NULL：异常消息是空白时不能落 null，否则留证与熔断计数一起静默失效。 */
    @Test
    void blankFailureMessageStillStoresNotNullReason() {
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(engine(0)));

        guard.recordFailure("glm-4-flash", "glm-4-flash", new RuntimeException("   "), "b7");

        ArgumentCaptor<AiEngineFault> captor = ArgumentCaptor.forClass(AiEngineFault.class);
        verify(faultRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("(无消息)");
    }

    @Test
    void failureStoresUpstreamStatusAndResponseExcerpt() {
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(engine(0)));

        guard.recordFailure("glm-4-flash", "glm-4.7-flash",
                CrawlException.upstream("免费判定(glm-4-flash)失败: 上游返回 429", 429,
                        "{\"error\":{\"code\":\"rpm_rate_limit_exceeded\"}}"), "b7");

        ArgumentCaptor<AiEngineFault> captor = ArgumentCaptor.forClass(AiEngineFault.class);
        verify(faultRepository).save(captor.capture());
        AiEngineFault saved = captor.getValue();
        assertThat(saved.getEngine()).isEqualTo("glm-4-flash");
        assertThat(saved.getModel()).isEqualTo("glm-4.7-flash");
        assertThat(saved.getKind()).isEqualTo("rate");
        assertThat(saved.getHttpStatus()).isEqualTo(429);
        assertThat(saved.getReason()).contains("429");
        assertThat(saved.getResponseExcerpt()).contains("rpm_rate_limit_exceeded");
        assertThat(saved.getBookId()).isEqualTo("b7");
        assertThat(saved.getOccurredTime()).isNotNull();
    }

    /** 连续到阈值就停用：默认 3 次、48 小时，且到期时间由库里带着，重启不丢。 */
    @Test
    void thirdConsecutiveFailureSuspendsEngineForTwoDays() {
        AiEngine nearlyDead = engine(2);
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(nearlyDead));

        guard.recordFailure("glm-4-flash", "glm-4-flash", new CrawlException("上游返回 503"), null);

        LocalDateTime until = nearlyDead.getDisableUntil();
        assertThat(until).isNotNull();
        assertThat(until).isBetween(DateUtil.now().plusHours(47), DateUtil.now().plusHours(49));
        assertThat(nearlyDead.getConsecutiveFailures()).isEqualTo(3);
        assertThat(nearlyDead.getDisableReason()).contains("连续 3 次失败").contains("503");
        assertThat(nearlyDead.isSuspended()).isTrue();
    }

    @Test
    void belowThresholdDoesNotSuspendYet() {
        AiEngine record = engine(0);
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        guard.recordFailure("glm-4-flash", "glm-4-flash", new CrawlException("上游返回 503"), null);

        assertThat(record.getConsecutiveFailures()).isEqualTo(1);
        assertThat(record.getDisableUntil()).isNull();
        assertThat(record.isSuspended()).isFalse();
    }

    /** 停用是为了"别再撞"，与是否参与熔断换引擎（autoBan）无关，所以按 token 计费的朱雀也要能停。 */
    @Test
    void autoBanFalseEngineStillGetsSuspended() {
        AiEngine zhuque = AiEngine.builder().id("zhuque-text").name("朱雀").baseUrl("https://y/classify")
                .protocol("classify").builtin(true).enabled(true).autoBan(false).priority(90)
                .consecutiveFailures(2).build();
        when(engineRepository.findById("zhuque-text")).thenReturn(Optional.of(zhuque));

        guard.recordFailure("zhuque-text", null, new CrawlException("上游返回 429"), null);

        assertThat(zhuque.getDisableUntil()).isNotNull();
    }

    @Test
    void successResetsFailureCounter() {
        AiEngine record = engine(2);
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        guard.recordSuccess("glm-4-flash");

        assertThat(record.getConsecutiveFailures()).isZero();
    }

    /** 没到期的停用不会被成功调用悄悄解除——它靠到期时间自然恢复，或用户在日志页手动解除。 */
    @Test
    void successKeepsSuspensionUntilItExpires() {
        AiEngine record = engine(5);
        record.setDisableUntil(DateUtil.now().plusHours(20));
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        guard.recordSuccess("glm-4-flash");

        assertThat(record.getConsecutiveFailures()).isZero();
        assertThat(record.isSuspended()).isTrue();
    }

    @Test
    void manualClearRestoresEngine() {
        AiEngine record = engine(3);
        record.setDisableUntil(DateUtil.now().plusHours(40));
        record.setDisableReason("连续 3 次失败：上游返回 429");
        when(engineRepository.findById("glm-4-flash")).thenReturn(Optional.of(record));

        guard.clearSuspension("glm-4-flash");

        assertThat(record.getDisableUntil()).isNull();
        assertThat(record.getDisableReason()).isNull();
        assertThat(record.getConsecutiveFailures()).isZero();
    }

    /** 留证写库失败绝不能把判定本身带崩。 */
    @Test
    void evidenceWriteFailureDoesNotBreakVerdictFlow() {
        when(faultRepository.save(any(AiEngineFault.class))).thenThrow(new RuntimeException("库锁了"));

        assertThatCode(() -> guard.recordFailure("glm-4-flash", "glm-4-flash",
                new CrawlException("上游返回 429"), "b1")).doesNotThrowAnyException();
        verify(engineRepository, never()).findById(anyString());
    }

    @Test
    void classifiesEveryFailureShape() {
        assertThat(AiFaultGuard.classify(401, "鉴权失败（401）：API Key 无效")).isEqualTo("auth");
        assertThat(AiFaultGuard.classify(403, "鉴权失败（403）")).isEqualTo("auth");
        assertThat(AiFaultGuard.classify(429, "上游返回 429")).isEqualTo("rate");
        assertThat(AiFaultGuard.classify(503, "上游返回 503")).isEqualTo("server");
        assertThat(AiFaultGuard.classify(0, "模型返回无法解析出 ai_probability")).isEqualTo("parse");
        assertThat(AiFaultGuard.classify(200, "免费判定返回空响应")).isEqualTo("empty");
        assertThat(AiFaultGuard.classify(0, "请求失败: read timed out")).isEqualTo("network");
        assertThat(AiFaultGuard.classify(0, "Connection reset")).isEqualTo("network");
        assertThat(AiFaultGuard.classify(0, "莫名其妙的失败")).isEqualTo("unknown");
    }
}
