package cn.seifly.jharness.plugins.rag.parser;

import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParser;

/**
 * PDF 解析器（.pdf）。底层依赖 PDFBox（随插件分发）。
 */
public class PdfDocumentParser extends AbstractTikaDocumentParser {

    @Override
    public boolean supports(String filename) {
        return endsWith(filename, "pdf");
    }

    @Override
    protected Parser createParser() {
        return new PDFParser();
    }
}
