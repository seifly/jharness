package cn.seifly.jharness.plugin.framework.security;

import cn.seifly.jharness.plugin.framework.service.Service;

import java.util.List;

/**
 * 安全服务契约。
 *
 * <p>由 workflow-plugin 在 {@code Plugin.start()} 内创建 {@link SecurityGuard} 实例后包装实现，
 * 通过 {@link cn.seifly.jharness.plugin.framework.service.ServiceRegistry} 注册，
 * 供 ui-plugin（如 ConfigController）在用户通过 API 调整安全策略时实时下发更新，
 * 不再编译期依赖 workflow-plugin。
 *
 * <p>本契约仅暴露「运行期策略更新」两个方法：
 * <ul>
 *   <li>{@link #updateRestrictToWorkspace}：切换工作空间沙箱开关；</li>
 *   <li>{@link #updateCommandBlacklist}：替换命令黑名单。</li>
 * </ul>
 * {@link SecurityGuard} 的路径 / 命令检查方法（{@code checkFilePath} / {@code checkCommand}）
 * 由持有实例的工具内部调用，无需通过本契约暴露。
 *
 * <p>消费方典型用法（ConfigController 更新安全配置后调用）：
 * <pre>{@code
 * services.get(SecurityService.class).ifPresent(s -> s.updateRestrictToWorkspace(flag));
 * }</pre>
 */
public interface SecurityService extends Service {

    /**
     * 更新工作空间沙箱开关。
     *
     * <p>实时生效：{@code true} 后续文件操作将被限制在工作空间内，
     * {@code false} 解除限制（仍保留受保护文件检查）。
     *
     * @param restrictToWorkspace 是否限制文件访问在工作空间内
     */
    void updateRestrictToWorkspace(boolean restrictToWorkspace);

    /**
     * 更新命令黑名单。
     *
     * <p>实时替换当前黑名单模式集合；传入 null 或空列表时恢复默认黑名单。
     *
     * @param customBlacklist 自定义命令黑名单正则模式列表
     */
    void updateCommandBlacklist(List<String> customBlacklist);
}
