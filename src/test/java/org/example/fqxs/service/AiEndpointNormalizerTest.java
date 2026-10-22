package org.example.fqxs.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端点归一与 SSRF 字面量校验（线上实测报告 §7.1 的回归）。
 *
 * <p>这些用例<b>不联网</b>：归一器只判字面量，域名解析在连接层（{@code GuardedDns}）。
 * 报告里被放行的 5 种写法（十进制整数、十六进制、点分十进制元数据、内网、解析到回环的公共域名）
 * 前四种在这里钉死，第五种在 {@code GuardedDnsTest} 里钉。</p>
 */
class AiEndpointNormalizerTest {

    @Test
    void rejectsDecimalIntegerEncodedMetadataAddress() {
        // 2852039166 = 169.254.169.254（云元数据），旧实现按文本前缀判断，完全看不见
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://2852039166/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字或十六进制 IP");
    }

    @Test
    void rejectsHexEncodedMetadataAddress() {
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://0xa9fea9fe/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字或十六进制 IP");
    }

    @Test
    void rejectsDecimalIntegerEncodedLoopback() {
        // 2130706433 = 127.0.0.1：即使指向本机也不接受数字写法，规范写法只有 localhost
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://2130706433/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字或十六进制 IP");
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://0x7f000001/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数字或十六进制 IP");
    }

    @Test
    void rejectsDottedPrivateAndLinkLocalAddresses() {
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://169.254.169.254/latest"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://192.168.1.10/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://10.0.0.5/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("https://100.64.0.1/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHttpForAnythingButCanonicalLoopbackNames() {
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("http://127.0.0.1.attacker.tld/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> AiEndpointNormalizer.normalize("http://localhost.evil.com/v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void keepsLocalOllamaWorking() {
        assertThat(AiEndpointNormalizer.normalize("http://localhost:11434/v1"))
                .isEqualTo("http://localhost:11434/v1/chat/completions");
        assertThat(AiEndpointNormalizer.normalize("http://127.0.0.1:11434"))
                .isEqualTo("http://127.0.0.1:11434/v1/chat/completions");
    }

    @Test
    void keepsPublicHttpsEndpointsWorking() {
        assertThat(AiEndpointNormalizer.normalize("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(AiEndpointNormalizer.normalize("https://open.bigmodel.cn/api/paas/v4"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(AiEndpointNormalizer.normalize("https://api.siliconflow.cn/v1/chat/completions"))
                .isEqualTo("https://api.siliconflow.cn/v1/chat/completions");
    }

    @Test
    void numericLiteralDetectionDoesNotSwallowNormalDomains() {
        assertThat(AiEndpointNormalizer.isNumericLiteral("2852039166")).isTrue();
        assertThat(AiEndpointNormalizer.isNumericLiteral("0xa9fea9fe")).isTrue();
        assertThat(AiEndpointNormalizer.isNumericLiteral("169.254.169.254")).isTrue();
        assertThat(AiEndpointNormalizer.isNumericLiteral("::1")).isTrue();
        // 域名里出现十六进制字母是正常的（abc.def / face.com），不能当成字面量
        assertThat(AiEndpointNormalizer.isNumericLiteral("abc.def")).isFalse();
        assertThat(AiEndpointNormalizer.isNumericLiteral("face.com")).isFalse();
        assertThat(AiEndpointNormalizer.isNumericLiteral("api.deepseek.com")).isFalse();
        assertThat(AiEndpointNormalizer.isNumericLiteral("localhost")).isFalse();
    }
}
