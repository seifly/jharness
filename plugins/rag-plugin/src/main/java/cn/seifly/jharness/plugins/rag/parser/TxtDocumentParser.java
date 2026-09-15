package cn.seifly.jharness.plugins.rag.parser;

import org.apache.tika.parser.Parser;
import org.apache.tika.parser.txt.TXTParser;

import static org.apache.commons.lang3.StringUtils.endsWith;

/**
 * 纯文本解析器（.txt）。
 */
public class TxtDocumentParser extends AbstractTikaDocumentParser {

    @Override
    public boolean supports(String filename) {
        return endsWith(filename, "txt");
    }

    @Override
    protected Parser createParser() {
        return new TXTParser();
    }
}
