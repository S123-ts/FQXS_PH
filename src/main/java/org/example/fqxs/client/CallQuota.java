package org.example.fqxs.client;

/**
 * 模型调用的配额闸门。
 *
 * <p>放在 {@code client} 包符合分层约束：{@code client} 不碰库，因此只依赖这个接口，
 * 真正的计数实现（读 {@code ai_usage}）在 {@code service.AiDailyQuota}。
 * 判定链路只有一个出网点（{@code AiDetectClient.executeOnce}），闸门挂在那里，
 * 新增的判定入口无需各自加检查。</p>
 */
public interface CallQuota {

    /**
     * 还能不能调：不能就抛 {@link org.example.fqxs.exception.QuotaExceededException}。
     *
     * <p>实现必须失败放行：配额不是安全控制（安全边界是登录门），
     * 数据库读不出来时不能阻断整条判定链路。</p>
     */
    void requireAvailable();
}
