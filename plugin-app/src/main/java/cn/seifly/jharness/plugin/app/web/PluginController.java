package cn.seifly.jharness.plugin.app.web;

import cn.seifly.jharness.plugin.app.core.PluginInfo;
import cn.seifly.jharness.plugin.app.core.SpringPluginManager;
import cn.seifly.jharness.plugin.app.service.GreetingService;
import cn.seifly.jharness.plugin.framework.GreetingExtension;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 插件管理 REST API。
 *
 * <p>覆盖插件全生命周期：上传 → 加载 → 启动 / 停止 → 启用 / 停用 → 卸载 → 删除。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class PluginController {

    private final SpringPluginManager pluginManager;
    private final GreetingService greetingService;

    @Value("${spring.pf4j.path:runtime-plugins}")
    private String pluginPath;

    public PluginController(SpringPluginManager pluginManager, GreetingService greetingService) {
        this.pluginManager = pluginManager;
        this.greetingService = greetingService;
    }

    // ---------------------------------------------------------------- 查询

    /** 查询所有已加载插件及状态 */
    @GetMapping("/plugins")
    public ApiResult<List<PluginInfo>> list() {
        List<PluginInfo> plugins = pluginManager.getPlugins().stream()
                .map(PluginInfo::from)
                .toList();
        return ApiResult.ok(plugins);
    }

    /** 重新扫描插件目录并加载新增插件（适用于手工拷贝 jar 到插件目录的场景） */
    @PostMapping("/plugins/reload")
    public ApiResult<List<PluginInfo>> reload() {
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
        return list();
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 上传并热加载插件：上传 jar → 加载 → 启动（若曾停用则先启用）。
     */
    @PostMapping("/plugins/upload")
    public ApiResult<PluginInfo> upload(@RequestParam("file") MultipartFile file) throws IOException {
        Path dir = Path.of(pluginPath).toAbsolutePath();
        Files.createDirectories(dir);

        String filename = sanitizeFilename(file.getOriginalFilename());
        if (!(filename.endsWith(".jar") || filename.endsWith(".zip"))) {
            throw new IllegalArgumentException("仅支持 .jar / .zip 格式的插件包: " + filename);
        }

        // 若同名插件已存在，先彻底移除（stop + unload + 删除旧文件），实现"覆盖升级"
        unloadIfLoaded(filename);

        Path target = dir.resolve(filename);
        file.transferTo(target);
        log.info("插件文件已保存: {}", target);

        // 加载（加载失败会抛出 PluginRuntimeException，由全局异常处理器统一返回）
        String pluginId = pluginManager.loadPlugin(target);
        if (PluginState.DISABLED.equals(pluginManager.getPluginState(pluginId))) {
            pluginManager.enablePlugin(pluginId);   // 恢复被停用的插件
        } else {
            pluginManager.startPlugin(pluginId);
        }

        PluginWrapper wrapper = pluginManager.getPlugin(pluginId);
        log.info("插件 [{}] 上传并启动成功，状态: {}", pluginId, wrapper.getPluginState());
        return ApiResult.ok(PluginInfo.from(wrapper));
    }

    // ---------------------------------------------------------------- 生命周期

    /** 加载（扫描并加载未加载的插件） */
    @PostMapping("/plugins/{id}/load")
    public ApiResult<String> load(@PathVariable String id) {
        pluginManager.loadPlugins();
        return stateResult(id);
    }

    /** 启动 */
    @PostMapping("/plugins/{id}/start")
    public ApiResult<String> start(@PathVariable String id) {
        if (!isLoaded(id)) {
            throw new IllegalArgumentException("插件 [" + id + "] 未加载，请先 Load");
        }
        pluginManager.startPlugin(id);
        return stateResult(id);
    }

    /** 停止 */
    @PostMapping("/plugins/{id}/stop")
    public ApiResult<String> stop(@PathVariable String id) {
        pluginManager.stopPlugin(id);
        return stateResult(id);
    }

    /** 启用（解除停用后自动重新加载并启动，恢复运行） */
    @PostMapping("/plugins/{id}/enable")
    public ApiResult<String> enable(@PathVariable String id) {
        pluginManager.enablePlugin(id);
        // enablePlugin 只解除停用标记并重新加载，这里补充 start 使其恢复运行
        if (isLoaded(id)) {
            pluginManager.startPlugin(id);
        }
        return stateResult(id);
    }

    /** 停用（stop + unload，标记为 DISABLED） */
    @PostMapping("/plugins/{id}/disable")
    public ApiResult<String> disable(@PathVariable String id) {
        pluginManager.disablePlugin(id);
        return stateResult(id);
    }

    /** 卸载（先停止） */
    @PostMapping("/plugins/{id}/unload")
    public ApiResult<String> unload(@PathVariable String id) {
        pluginManager.unloadPlugin(id);
        return stateResult(id);
    }

    /** 删除（停止 + 卸载 + 删除插件文件） */
    @DeleteMapping("/plugins/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        boolean deleted = pluginManager.deletePlugin(id);
        log.info("插件 [{}] 已删除: {}", id, deleted);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 业务演示

    /** 当前注册的问候扩展实现类列表 */
    @GetMapping("/extensions")
    public ApiResult<List<String>> extensions() {
        return ApiResult.ok(greetingService.extensionClasses());
    }

    /** 调用全部问候扩展（观察插件的启停对业务即时生效） */
    @GetMapping("/greeting")
    public ApiResult<List<String>> greet(@RequestParam(defaultValue = "world") String name) {
        List<GreetingExtension> extensions = pluginManager.getExtensions(GreetingExtension.class);
        if (extensions.isEmpty()) {
            return ApiResult.error(404, "当前没有任何已启动的问候插件，请先上传并启动插件");
        }
        return ApiResult.ok(greetingService.greet(name));
    }

    // ---------------------------------------------------------------- 工具方法

    private boolean isLoaded(String id) {
        return pluginManager.getPlugin(id) != null;
    }

    private ApiResult<String> stateResult(String id) {
        PluginState state = pluginManager.getPluginState(id);
        return ApiResult.ok("插件 [" + id + "] 当前状态: " + state);
    }

    /** 若同名插件文件已加载，先整体删除（stop + unload + 删文件），实现覆盖式升级 */
    private void unloadIfLoaded(String filename) {
        for (PluginWrapper wrapper : pluginManager.getPlugins()) {
            Path path = wrapper.getPluginPath();
            if (path != null && path.getFileName().toString().equals(filename)) {
                String id = wrapper.getDescriptor().getPluginId();
                pluginManager.deletePlugin(id);
                log.info("已移除旧版本插件 [{}]，准备覆盖升级", id);
            }
        }
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("上传文件名不能为空");
        }
        // 防目录穿越
        String cleaned = Path.of(original).getFileName().toString();
        return cleaned.replaceAll("\\s+", "_");
    }
}
