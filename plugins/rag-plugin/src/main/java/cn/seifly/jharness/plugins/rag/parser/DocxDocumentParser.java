package cn.seifly.jharness.plugins.rag.parser;

import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;

/**
 * Word 解析器（.docx）。底层依赖 Apache POI（随插件分发）。
 */
public class DocxDocumentParser extends AbstractTikaDocumentParser {

    @Override
    public boolean supports(String filename) {
        return endsWith(filename, "docx");
    }

    @Override
    protected Parser createParser() {
        return new OOXMLParser();
    }
}
