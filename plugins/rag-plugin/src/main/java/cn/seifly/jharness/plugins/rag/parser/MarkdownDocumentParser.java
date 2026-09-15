package cn.seifly.jharness.plugins.rag.parser;

import org.apache.tika.parser.Parser;

/**
 * Markdown 解析器（.md / .markdown）。
 *
 * <p>Tika 2.x 不再内置 Markdown 解析器，这里使用自实现的 {@link MarkdownTextParser}
 * （仅依赖 tika-core，剥离标记输出纯文本，供 RAG 切片）。
 */
public class MarkdownDocumentParser extends AbstractTikaDocumentParser {

    @Override
    public boolean supports(String filename) {
        return endsWith(filename, "md", "markdown");
    }

    @Override
    protected Parser createParser() {
        return new MarkdownTextParser();
    }
}
