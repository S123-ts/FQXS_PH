package org.example.fqxs.client;

import okhttp3.Dns;
import org.example.fqxs.util.HostAddressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 带 SSRF 闸门的 DNS 解析器：OkHttp 每次建连前都会走这里，解析结果不合法就直接断。
 *
 * <p>为什么闸门放在这一层而不是只放在保存配置时：域名保存时解析成公网 IP、请求时解析成内网 IP
 * （DNS rebinding）是绕过"保存时校验"的标准手法，只有<b>真正要连接的那一刻</b>解析出来的地址
 * 才作数。放在 {@link Dns} 里还有一个好处——判定覆盖所有走这个 client 的出口
 * （判定 / 测试引擎 / 拉取型号），不需要每个出口各自记得检查。</p>
 *
 * <p>规则见 {@link HostAddressPolicy}：内网、链路本地（云元数据 169.254.169.254 在这里）、
 * 运营商级 NAT、保留段、组播一律拒绝；回环只放行规范写法的 {@code localhost} / {@code 127.0.0.1} /
 * {@code ::1}，解析到回环的公共域名（如 {@code localtest.me}）同样拒绝。</p>
 */
public final class GuardedDns implements Dns {

    private final Dns delegate;

    /** 生产用：底层走系统解析。 */
    public GuardedDns() {
        this(Dns.SYSTEM);
    }

    /**
     * 测试用：注入可控的解析结果，避免单测依赖真实 DNS。
     *
     * @param delegate 实际解析器
     */
    GuardedDns(Dns delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<InetAddress> addresses = delegate.lookup(hostname);
        if (addresses == null || addresses.isEmpty()) {
            throw new UnknownHostException("目标主机没有解析到任何地址：" + hostname);
        }
        boolean allLoopback = addresses.stream().allMatch(HostAddressPolicy::isLoopbackAddress);
        if (allLoopback) {
            if (HostAddressPolicy.isLoopbackName(hostname)) {
                return addresses;
            }
            throw new UnknownHostException(
                    "目标主机解析到本机回环地址，只允许 localhost / 127.0.0.1：" + hostname);
        }
        for (InetAddress address : addresses) {
            if (HostAddressPolicy.isBlockedAddress(address)) {
                throw new UnknownHostException("目标主机解析到内网/链路本地地址，已拒绝：" + hostname);
            }
        }
        return addresses;
    }
}
