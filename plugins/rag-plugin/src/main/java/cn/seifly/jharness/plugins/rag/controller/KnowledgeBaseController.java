package cn.seifly.jharness.plugins.rag.controller;

import cn.seifly.jharness.plugins.rag.core.api.KnowledgeBaseService;
import cn.seifly.jharness.plugins.rag.core.model.KnowledgeBase;
import cn.seifly.jharness.plugins.rag.core.model.RagResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理 REST 控制器（{@code /api/rag/knowledge-bases}）。
 *
 * <p>与 {@link RagController} 一致：由插件私有子上下文构造注入后，
 * 在 {@code RagPlugin.start()} 中手动挂载到主应用 MVC，不使用 {@code @Autowired} 字段。
 */
@RestController
@RequestMapping("/api/rag")
@Slf4j
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    public KnowledgeBaseController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/knowledge-bases")
    public RagResponse<List<KnowledgeBase>> list() {
        return RagResponse.ok(kbService.list());
    }

    @PostMapping("/knowledge-bases")
    public RagResponse<KnowledgeBase> create(@RequestBody KnowledgeBaseService.CreateRequest req) {
        return RagResponse.ok(kbService.create(req));
    }

    @DeleteMapping("/knowledge-bases/{id}")
    public RagResponse<String> delete(@PathVariable("id") String id) {
        kbService.delete(id);
        return RagResponse.ok("deleted:" + id);
    }
}
