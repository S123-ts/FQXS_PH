package org.example.fqxs.client;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 连接层 SSRF 闸门。
 *
 * <p>归一器只判字面量，域名能解析到哪只有建连时才知道；这里注入可控的解析结果，
 * 不依赖真实 DNS，也就不会因为测试机器在哪个网段而变绿或变红。</p>
 */
class GuardedDnsTest {

    private static Dns resolvingTo(String... addresses) {
        return hostname -> {
            InetAddress[] parsed = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                parsed[i] = InetAddress.getByName(addresses[i]);
            }
            return List.of(parsed);
        };
    }

    @Test
    void rejectsPublicNameThatResolvesToLoopback() {
        // localtest.me 是解析到 127.0.0.1 的公共域名：保存时看不出问题，建连时才有地址
        GuardedDns dns = new GuardedDns(resolvingTo("127.0.0.1"));
        assertThatThrownBy(() -> dns.lookup("localtest.me"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("回环");
    }

    @Test
    void rejectsNameThatResolvesToCloudMetadata() {
        GuardedDns dns = new GuardedDns(resolvingTo("169.254.169.254"));
        assertThatThrownBy(() -> dns.lookup("169.254.169.254.nip.io"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void rejectsPrivateAndCarrierGradeNatAddresses() {
        assertThatThrownBy(() -> new GuardedDns(resolvingTo("10.0.0.5")).lookup("ollama.lan"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new GuardedDns(resolvingTo("192.168.1.20")).lookup("gateway.lan"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new GuardedDns(resolvingTo("100.64.0.1")).lookup("cgnat.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsWhenOneOfSeveralAnswersIsPrivate() {
        // 解析出多个地址时只要有一个落在内网就整体拒绝，避免攻击者用"先公网后内网"的顺序碰运气
        GuardedDns dns = new GuardedDns(resolvingTo("1.2.3.4", "10.0.0.5"));
        assertThatThrownBy(() -> dns.lookup("rebind.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void allowsCanonicalLoopbackAndPublicHosts() throws UnknownHostException {
        assertThat(new GuardedDns(resolvingTo("127.0.0.1")).lookup("localhost"))
                .hasSize(1);
        assertThat(new GuardedDns(resolvingTo("::1")).lookup("localhost"))
                .hasSize(1);
        assertThat(new GuardedDns(resolvingTo("1.2.3.4")).lookup("api.deepseek.com"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("1.2.3.4");
    }

    @Test
    void propagatesResolutionFailureAndEmptyAnswers() {
        Dns broken = hostname -> {
            throw new UnknownHostException("模拟解析失败");
        };
        assertThatThrownBy(() -> new GuardedDns(broken).lookup("no-such-host.invalid"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new GuardedDns(hostname -> List.of()).lookup("empty.example.com"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("没有解析到任何地址");
    }
}
