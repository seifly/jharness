package cn.seifly.jharness.plugins.rag.parser;

import cn.seifly.jharness.plugins.rag.core.api.DocumentParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;

/**
 * 基于 Apache Tika 的解析基类。
 *
 * <p>具体解析器只需提供对应的 Tika {@link Parser}（PDF / DOCX / TXT / Markdown），
 * 基类统一完成「输入流 → 纯文本」的转换。引用 Schema 等统一逻辑放这里。
 */
public abstract class AbstractTikaDocumentParser implements DocumentParser {

    /**
     * 子类提供的具体 Tika Parser 实例（每次解析建议新建，避免并发共享状态）。
     */
    protected abstract Parser createParser();

    @Override
    public String parse(InputStream inputStream) throws Exception {
        // -1 表示不限制输出长度（默认 100KB 会截断大文档）
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        try (InputStream in = inputStream) {
            createParser().parse(in, handler, metadata, context);
        }
        return handler.toString();
    }

    /**
     * 判断文件名是否以给定扩展名结尾（大小写不敏感）。
     */
    protected boolean endsWith(String filename, String... extensions) {
        if (filename == null) {
            return false;
        }
        String name = filename.toLowerCase();
        for (String ext : extensions) {
            if (ext != null && name.endsWith("." + ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
