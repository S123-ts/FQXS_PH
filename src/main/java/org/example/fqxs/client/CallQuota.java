package org.example.fqxs.client;

/**
 * 模型调用的配额闸门。
 *
 * <p>放在 {@code client} 包是为了守住分层约定：{@code client} 不碰库，所以它只依赖这个接口，
 * 真正的计数实现（读 {@code ai_usage}）在 {@code service.AiDailyQuota}。
 * 判定链路只有一个出网点（{@code AiDetectClient.executeOnce}），闸门挂在那里，
 * 新增的判定入口不用各自记得加检查。</p>
 */
public interface CallQuota {

    /**
     * 还能不能调：不能就抛 {@link org.example.fqxs.exception.QuotaExceededException}。
     *
     * <p>实现必须是「失败放行」的——配额不是安全控制（登录门才是），
     * 数据库读不出来时不能把整条判定链路一起拖死。</p>
     */
    void requireAvailable();
}
