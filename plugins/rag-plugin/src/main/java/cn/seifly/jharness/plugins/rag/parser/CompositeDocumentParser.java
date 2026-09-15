package cn.seifly.jharness.plugins.rag.parser;

import cn.seifly.jharness.plugins.rag.core.api.DocumentParser;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;
import java.util.List;

/**
 * 组合解析器（Parser Registry）：按文件名分发给具体解析器。
 *
 * <p>上传入口只依赖本组合解析器；新增文件类型只需注入新的 {@link DocumentParser} 实现，
 * 上传编排逻辑零修改（开闭原则）。
 *
 * <p>文件名通过 {@link #parse(String, InputStream)} 随调用一起传入用于分发，
 * 不再依赖可变字段（避免并发下文件名串号）。
 */
public class CompositeDocumentParser implements DocumentParser {

    private final List<DocumentParser> parsers;

    public CompositeDocumentParser(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    @Override
    public boolean supports(String filename) {
        return parsers.stream().anyMatch(p -> p.supports(filename));
    }

    @Override
    public String parse(InputStream inputStream) throws Exception {
        // 组合解析器必须已知文件名才能分发；请改用 parse(String, InputStream)
        return parse(null, inputStream);
    }

    /**
     * 带文件名的解析入口：按文件名匹配第一个支持的子解析器并分发。
     */
    public String parse(String filename, InputStream inputStream) throws Exception {
        for (DocumentParser parser : parsers) {
            if (parser.supports(filename)) {
                return parser.parse(inputStream);
            }
        }
        throw new RagException(RagErrorCode.RAG_FILE_UNSUPPORTED, "不支持的文件类型: " + filename);
    }
}
