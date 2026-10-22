package org.example.fqxs.util;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;

/**
 * 主机地址安全策略：SSRF 防护的<b>唯一判定点</b>，保存端点时与真正连接时共用。
 *
 * <p>拆出来是因为两个调用方在不同层：{@code service/AiEndpointNormalizer} 在保存配置时判字面量，
 * {@code client/GuardedDns} 在 OkHttp 解析域名后判真实地址。判定规则必须只有一份，
 * 否则两边各写一遍必然漂移（历史教训：文本前缀黑名单只挡住了 {@code 169.254.} 开头的写法，
 * 却放过了 {@code 2852039166} 这种十进制整数编码）。</p>
 */
public final class HostAddressPolicy {

    /**
     * 允许明文 http 的本机名字白名单。
     *
     * <p>只认这三个<b>规范写法</b>：{@code localhost} / {@code 127.0.0.1} / {@code ::1}。
     * 数字编码（{@code 2130706433}）、十六进制（{@code 0x7f000001}）、
     * 解析到回环的公共域名（{@code localtest.me}）都不在里面——它们唯一的作用就是绕过字符串判断。</p>
     */
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private HostAddressPolicy() {
    }

    /**
     * 是否是允许的本机名字（规范化比较，大小写不敏感）。
     *
     * @param host URI 里的 host 部分
     * @return true 表示这是规范写法的本机地址
     */
    public static boolean isLoopbackName(String host) {
        return host != null && LOOPBACK_NAMES.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * 是否回环地址（127.0.0.0/8、::1）。
     *
     * @param address 已解析的地址
     * @return true 表示是本机回环
     */
    public static boolean isLoopbackAddress(InetAddress address) {
        return address != null && address.isLoopbackAddress();
    }

    /**
     * 是否是 SSRF 需要拦截的地址：内网、链路本地、保留段、组播、未指定地址。
     *
     * <p>不用 {@code isSiteLocalAddress()} 单打独斗：它漏掉链路本地（169.254/16，云元数据就在这里）、
     * 运营商级 NAT（100.64/10）与若干保留段。</p>
     *
     * @param address 已解析的地址
     * @return true 表示必须拒绝
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            int b2 = raw[2] & 0xFF;
            // 0.0.0.0/8（含 Linux 上等于"本机"的 0.0.0.0）
            if (b0 == 0) {
                return true;
            }
            // 100.64/10 运营商级 NAT：容器/云内网常见，元数据也可能落这里
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 192.0.0/24 IETF 协议分配、198.18/15 基准测试段、240/4 保留
            if (b0 == 192 && b1 == 0 && b2 == 0) {
                return true;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            return b0 >= 240;
        }
        if (raw.length == 16) {
            // fc00::/7 唯一本地地址（IPv6 的内网段）
            return (raw[0] & 0xFE) == 0xFC;
        }
        return false;
    }
}
