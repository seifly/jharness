package cn.seifly.jharness.plugins.rag.core.api;

import org.pf4j.ExtensionPoint;

import java.io.InputStream;

/**
 * 文档解析 SPI。
 *
 * <p>统一把各类文档解析为纯文本；具体实现按文件类型分发
 * （TXT / Markdown / PDF / DOCX ...）。继承 PF4J {@link ExtensionPoint}。
 */
public interface DocumentParser extends ExtensionPoint {

    /**
     * 是否支持该文件名（按扩展名判断）。
     */
    boolean supports(String filename);

    /**
     * 解析输入流为纯文本。
     *
     * @throws Exception 解析失败（文件损坏 / 格式不支持 / 编码异常等）
     */
    String parse(InputStream inputStream) throws Exception;

    /**
     * 带文件名的解析入口（组合解析器据此按文件名分发到具体实现）。
     *
     * <p>单文件解析器（如 Tika 系）按内容自动探测类型、不依赖文件名，故提供默认实现直接忽略文件名。
     * {@link cn.seifly.jharness.plugins.rag.parser.CompositeDocumentParser} 会覆盖此方法完成分发。
     */
    default String parse(String filename, InputStream inputStream) throws Exception {
        return parse(inputStream);
    }
}
