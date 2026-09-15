package cn.seifly.jharness.plugins.ui.controller;

import cn.seifly.jharness.plugins.skills.SkillInfo;
import cn.seifly.jharness.plugins.skills.SkillsLoader;
import cn.seifly.jharness.plugins.ui.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理 API 控制器
 *
 * <p>提供技能的查询、创建、更新和删除功能。对应 doc/src/.../web/controller/SkillsController 的功能：
 * <ul>
 *   <li>{@code GET    /api/skills}      —— 列出全部技能（workspace &gt; global &gt; builtin）</li>
 *   <li>{@code GET    /api/skills/{name}} —— 读取指定技能内容（去除 YAML 前置元数据）</li>
 *   <li>{@code PUT    /api/skills/{name}} —— 保存/更新工作空间技能（写入 workspace/skills/{name}/SKILL.md）</li>
 *   <li>{@code DELETE /api/skills/{name}} —— 删除工作空间技能（仅可删 workspace 来源）</li>
 * </ul>
 *
 * <p>迁移说明：技能数据由文件系统驱动，通过 {@link SkillsLoader} 读写 SKILL.md，
 * 不再使用 Redis（{@code SKILLS_INSTALLED}）存储技能列表/内容。
 * {@link SkillsLoader} 来自 skills-plugin，运行期通过 {@code plugin.dependencies=skills-plugin}
 * 将其类挂入 ui-plugin 类加载器父链共享（与 tools-plugin / workflow-plugin 同机制）。
 * workspace 路径通过 {@code @Value} 注入并由 {@link WebUtils#expandHome} 展开 ~。
 */
@RestController
@RequestMapping("/api/skills")
@CrossOrigin(origins = "${jclaw.gateway.cors-origin:*}", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
@Slf4j
public class SkillsController {

    @Value("${jclaw.agent.workspace:~/.jclaw/workspace}")
    private String workspacePath;

    /** 懒构造的 SkillsLoader，需在 @Value 注入后构建（控制器由 registry autowireBean 注入字段） */
    private volatile SkillsLoader skillsLoader;

    /**
     * 获取所有技能列表
     *
     * @return 技能列表，包含 name、description、source、path
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<SkillInfo> skills = getSkillsLoader().listSkills();
        List<Map<String, Object>> result = new ArrayList<>();

        for (SkillInfo skill : skills) {
            Map<String, Object> skillNode = new HashMap<>();
            skillNode.put("name", skill.getName());
            skillNode.put("description", skill.getDescription() != null ? skill.getDescription() : "");
            skillNode.put("source", skill.getSource());
            skillNode.put("path", skill.getPath());
            result.add(skillNode);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定技能的内容
     *
     * @param name 技能名称（URL 编码）
     * @return 技能内容
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable("name") String name) {
        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            String content = getSkillsLoader().loadSkill(decodedName);

            if (content != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("name", decodedName);
                result.put("content", content);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Skill not found");
                return ResponseEntity.status(404).body(error);
            }
        } catch (Exception e) {
            log.error("Skills API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 保存或更新技能
     *
     * @param name 技能名称（URL 编码）
     * @param request 包含 content 的请求体
     * @return 保存结果
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> saveSkill(
            @PathVariable("name") String name,
            @RequestBody Map<String, Object> request) {

        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);
            String content = request.containsKey("content") ? (String) request.get("content") : null;

            if (content == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing content");
                return ResponseEntity.status(400).body(error);
            }

            if (getSkillsLoader().saveWorkspaceSkill(decodedName, content)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Failed to save skill");
                return ResponseEntity.status(500).body(error);
            }
        } catch (Exception e) {
            log.error("Skills API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 删除技能
     *
     * 注意：只能删除 workspace 中的技能，不能删除内置技能。
     *
     * @param name 技能名称（URL 编码）
     * @return 删除结果
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(@PathVariable("name") String name) {
        try {
            String decodedName = URLDecoder.decode(name, StandardCharsets.UTF_8);

            if (getSkillsLoader().deleteWorkspaceSkill(decodedName)) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                return ResponseEntity.ok(result);
            } else {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Skill not found or not a workspace skill");
                return ResponseEntity.status(404).body(error);
            }
        } catch (Exception e) {
            log.error("Skills API error: error={}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * 懒构造 SkillsLoader 实例。
     *
     * <p>workspace 路径在 {@code @Value} 注入后可用，SkillsLoader 仅持有路径字符串，
     * 构造开销极低；使用双重检查保证单例，避免并发请求重复构造。
     */
    private SkillsLoader getSkillsLoader() {
        SkillsLoader loader = skillsLoader;
        if (loader == null) {
            synchronized (this) {
                loader = skillsLoader;
                if (loader == null) {
                    loader = new SkillsLoader(WebUtils.expandHome(workspacePath), null, null);
                    skillsLoader = loader;
                }
            }
        }
        return loader;
    }
}
