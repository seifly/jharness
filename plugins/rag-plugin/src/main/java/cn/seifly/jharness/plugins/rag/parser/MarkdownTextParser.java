package cn.seifly.jharness.plugins.rag.parser;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/**
 * Markdown → 纯文本 的 Tika {@link Parser} 实现。
 *
 * <p>Apache Tika 2.x 已不再内置 Markdown 解析器（{@code org.apache.tika.parser.markdown.MarkdownParser}
 * 在 1.x 中存在、2.x 被移除），这里自行实现一个轻量版本：剥离常见 Markdown 标记（标题 / 引用 /
 * 列表 / 强调 / 链接 / 行内代码 / HTML 标签），输出适合 RAG 切片的纯文本。
 *
 * <p>不引入额外依赖，仅依赖 tika-core。
 */
public class MarkdownTextParser implements Parser {

    private static final long serialVersionUID = 1L;

    private static final Set<MediaType> SUPPORTED_TYPES =
            Collections.singleton(MediaType.parse("text/markdown"));

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext context) {
        return SUPPORTED_TYPES;
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {
        String md = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String plainText = toPlainText(md);

        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();
        xhtml.characters(plainText);
        xhtml.endDocument();
    }

    /**
     * 逐行剥离 Markdown 标记，输出纯文本。
     */
    private static String toPlainText(String md) {
        StringBuilder sb = new StringBuilder();
        for (String raw : md.split("\n", -1)) {
            String line = raw;
            // 标题 # ~ ######
            line = line.replaceAll("^#{1,6}\\s+", "");
            // 引用 >
            line = line.replaceAll("^>\\s?", "");
            // 无序列表 - * +
            line = line.replaceAll("^\\s*[-*+]\\s+", "");
            // 有序列表 1.
            line = line.replaceAll("^\\s*\\d+\\.\\s+", "");
            // 链接 [text](url) -> text；图片 ![alt](url) -> alt
            line = line.replaceAll("!\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
            line = line.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
            // 粗体 / 斜体 / 删除线标记
            line = line.replaceAll("[*_~]{1,3}", "");
            // 行内代码反引号
            line = line.replaceAll("`", "");
            // 残留 HTML 标签
            line = line.replaceAll("<[^>]+>", "");
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
