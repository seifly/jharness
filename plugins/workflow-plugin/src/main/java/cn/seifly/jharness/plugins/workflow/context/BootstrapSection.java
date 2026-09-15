package cn.seifly.jharness.plugins.workflow.context;

import lombok.extern.slf4j.Slf4j;
import cn.seifly.jharness.plugin.framework.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 引导文件部分。
 * 从工作空间加载 AGENTS.md、SOUL.md、USER.md、IDENTITY.md 等自定义配置文件。
 */
@Slf4j
public class BootstrapSection implements ContextSection {

    private static final List<String> BOOTSTRAP_FILES = List.of(
            "AGENTS.md", "SOUL.md", "USER.md"
    );

    @Override
    public String name() {
        return "Bootstrap";
    }

    @Override
    public String build(SectionContext context) {
        StringBuilder result = new StringBuilder();

        for (String filename : BOOTSTRAP_FILES) {
            String content = loadBootstrapFile(context.getWorkspace(), filename);
            if (StringUtils.isNotBlank(content)) {
                result.append("## ").append(filename).append("\n\n");
                result.append(content).append("\n\n");
            }
        }

        return result.toString();
    }

    /**
     * 加载单个引导文件。
     */
    private String loadBootstrapFile(String workspace, String filename) {
        try {
            String filePath = Paths.get(workspace, filename).toString();
            if (Files.exists(Paths.get(filePath))) {
                return Files.readString(Paths.get(filePath));
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to load bootstrap file: filename={}, error={}",
                        filename, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        return "";
    }
}
