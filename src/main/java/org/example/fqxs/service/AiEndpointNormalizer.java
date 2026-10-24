package org.example.fqxs.service;

import org.example.fqxs.util.HostAddressPolicy;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容端点的归一与 SSRF 校验。
 *
 * <p>从 AiEngineRegistry 抽出：只在保存引擎时使用，不依赖任何 Spring bean，
 * 便于单独测试。规则：裸主机补 {@code /v1/chat/completions}；以 {@code /vN} 结尾
 * 补 {@code /chat/completions}；仅规范写法的 localhost/127.0.0.1/::1 允许明文 http；
 * 数字与十六进制 IP 写法一律拒绝。</p>
 *
 * <p><b>这里只判字面量，不做 DNS 解析</b>。两个理由：</p>
 * <ul>
 *   <li>保存配置不应依赖网络：域名一时解析失败就会阻断保存；</li>
 *   <li>域名能解析到哪，只有真正连接时才知道——DNS rebinding 的原理正是"保存时是公网 IP、
 *       请求时变成内网 IP"。真正的拦截点在连接层：{@link org.example.fqxs.client.GuardedDns}
 *       在 OkHttp 每次解析后按 {@link HostAddressPolicy} 判一遍真实地址。</li>
 * </ul>
 *
 * <p>纯文本前缀黑名单（只认 {@code 10.} / {@code 192.168.} / {@code 127.} / {@code 169.254.}
 * 开头）会放行 {@code https://2852039166/v1}（十进制整数编码的 169.254.169.254）、
 * {@code https://0xa9fea9fe/v1}（十六进制）与 {@code https://localtest.me/v1}（解析到回环的
 * 公共域名）。因此字面量写法直接拒绝，域名交给连接层判定。</p>
 */
public final class AiEndpointNormalizer {

    /** 十进制整数写法：{@code 2852039166} = 169.254.169.254。 */
    private static final Pattern INTEGER_LITERAL = Pattern.compile("^\\d+$");

    /** 十六进制整数写法：{@code 0xa9fea9fe}。 */
    private static final Pattern HEX_LITERAL = Pattern.compile("^0[xX][0-9a-fA-F]+$");

    /** 点分十进制：{@code 169.254.169.254}。 */
    private static final Pattern DOTTED_LITERAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** 混合写法：{@code 0xa9.0xfe.0xa9.0xfe}。 */
    private static final Pattern MIXED_LITERAL = Pattern.compile("^0[xX][0-9a-fA-F]+(\\.[0-9a-fA-F]+)*$");

    private AiEndpointNormalizer() {
    }

    /**
     * 归一用户填写的接口地址。
     *
     * @param raw 用户输入
     * @return 归一后的完整 chat 端点
     * @throws IllegalArgumentException scheme/主机地址不合法
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        String url = raw.trim();
        if (url.contains("?") || url.contains("#") || url.contains("@")) {
            throw new IllegalArgumentException("接口地址不能带查询串、锚点或内嵌账号密码");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("接口地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isBlank()) {
            throw new IllegalArgumentException("接口地址缺少主机名");
        }
        // 只有规范写法的本机名字允许明文 http；localhost.evil.com / 127.0.0.1.attacker.tld 都不算
        boolean loopback = HostAddressPolicy.isLoopbackName(host);
        // 数字/十六进制 IP 写法一律拒绝：它们唯一的作用就是绕过字符串判断。
        // 要指向本机请写 localhost——三个规范写法已在白名单里，不会被这条挡下
        if (!loopback && isNumericLiteral(host)) {
            throw new IllegalArgumentException(
                    "接口地址必须写域名，不能用数字或十六进制 IP 写法（要指向本机请写 localhost）");
        }
        if (!"https".equals(scheme) && !(loopback && "http".equals(scheme))) {
            throw new IllegalArgumentException("接口地址必须是 https（本机 Ollama 可用 http://localhost）");
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        if (url.matches(".*/v\\d+$")) {
            return url + "/chat/completions";
        }
        return url + "/v1/chat/completions";
    }

    /**
     * 是否是 IP 字面量写法（含 IPv6）。
     *
     * <p>纯文本判断，不解析也不联网：真实地址的判定在连接层做。</p>
     *
     * @param host URI 里的 host
     * @return true 表示这是 IP 字面量而不是域名
     */
    static boolean isNumericLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // 带冒号只可能是 IPv6 字面量（域名里不允许冒号）
        return host.indexOf(':') >= 0
                || INTEGER_LITERAL.matcher(host).matches()
                || HEX_LITERAL.matcher(host).matches()
                || DOTTED_LITERAL.matcher(host).matches()
                || MIXED_LITERAL.matcher(host).matches();
    }
}
