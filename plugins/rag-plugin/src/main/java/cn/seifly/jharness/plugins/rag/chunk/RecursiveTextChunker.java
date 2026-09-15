package cn.seifly.jharness.plugins.rag.chunk;

import cn.seifly.jharness.plugins.rag.core.api.TextChunker;
import cn.seifly.jharness.plugins.rag.core.exception.RagErrorCode;
import cn.seifly.jharness.plugins.rag.core.exception.RagException;
import cn.seifly.jharness.plugins.rag.core.model.DocumentChunk;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归语义切分器（默认 {@link TextChunker} 实现）。
 *
 * <p>切分优先级（保证语义完整性，避免简单 substring）：
 * <pre>
 *   段落 (blank-line separated)
 *     ↓（单段超限）
 *   标题 / 句子
 *     ↓（单句超限）
 *   固定长度（带 overlap）
 * </pre>
 *
 * <p>overlap：每个 Chunk 末尾的 {@code overlap} 个字符会被携带到下一个 Chunk 开头，
 * 保证跨边界语义连续。token 数无法精确计算时，以「字符数」近似。
 */
public class RecursiveTextChunker implements TextChunker {

    private final int chunkSize;
    private final int overlap;

    public RecursiveTextChunker(int chunkSize, int overlap) {
        if (chunkSize <= overlap) {
            throw new IllegalArgumentException("chunkSize 必须大于 overlap");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<DocumentChunk> split(String documentId, String text) {
        if (StringUtils.isBlank(text)) {
            throw new RagException(RagErrorCode.RAG_CHUNK_EMPTY);
        }

        // 1) 段落级切分
        List<String> paragraphs = splitParagraphs(text);

        // 2) 把段落进一步规整为"不超过 chunkSize 的片段"（段落/句子/硬切）
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (current.length() + p.length() + 1 <= chunkSize) {
                appendWithSpace(current, p);
                continue;
            }
            // 当前段落装不下：先把已积累内容落成一个片段
            if (current.length() > 0) {
                segments.add(current.toString());
                current.setLength(0);
            }
            if (p.length() <= chunkSize) {
                // 整段作为一个新片段起点
                current.append(p);
            } else {
                // 段落超限 → 句子级切分
                for (String sentence : splitSentences(p)) {
                    if (current.length() + sentence.length() + 1 <= chunkSize) {
                        appendWithSpace(current, sentence);
                    } else {
                        if (current.length() > 0) {
                            segments.add(current.toString());
                            current.setLength(0);
                        }
                        if (sentence.length() <= chunkSize) {
                            current.append(sentence);
                        } else {
                            // 单句仍超限 → 固定长度硬切（带 overlap，由外层统一处理）
                            for (String hard : hardSplit(sentence, chunkSize)) {
                                segments.add(hard);
                            }
                        }
                    }
                }
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }

        if (segments.isEmpty()) {
            throw new RagException(RagErrorCode.RAG_CHUNK_EMPTY);
        }

        // 3) 组装 Chunk + overlap 携带
        List<DocumentChunk> chunks = new ArrayList<>();
        String carry = "";
        int idx = 0;
        for (String seg : segments) {
            String content = carry.isEmpty() ? seg : (carry + "\n" + seg);
            if (content.length() > chunkSize + overlap) {
                // 极端情况下裁剪（不应发生）
                content = content.substring(0, chunkSize + overlap);
            }
            chunks.add(DocumentChunk.builder()
                    .id(documentId + "-" + idx)
                    .documentId(documentId)
                    .chunkIndex(idx)
                    .content(content)
                    .build());
            // 记录本 chunk 末尾 overlap 字符作为下一个 chunk 的 carry
            carry = content.length() <= overlap ? content : content.substring(content.length() - overlap);
            idx++;
        }
        return chunks;
    }

    // ----------------------- 内部工具 -----------------------

    private static void appendWithSpace(StringBuilder sb, String piece) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(piece);
    }

    /**
     * 按空行 / 标题切分为段落。
     */
    private List<String> splitParagraphs(String text) {
        List<String> result = new ArrayList<>();
        // 标题单独成段（# / ## ...）
        String[] byBlank = text.split("\\n\\s*\\n");
        for (String block : byBlank) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 把以 # 开头的行作为独立段落，避免与正文混切
            if (trimmed.startsWith("#")) {
                for (String line : trimmed.split("\\n")) {
                    String l = line.trim();
                    if (!l.isEmpty()) {
                        result.add(l);
                    }
                }
            } else {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * 按中英文句末标点切分为句子。
     */
    private List<String> splitSentences(String paragraph) {
        List<String> result = new ArrayList<>();
        // 保留分隔符：在句末标点后切分
        String[] parts = paragraph.split("(?<=[。！？!?；;\\n])");
        for (String part : parts) {
            String p = part.trim();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * 超长片段按固定长度硬切（不做 overlap，overlap 由上层 carry 机制统一保证）。
     */
    private List<String> hardSplit(String sentence, int size) {
        List<String> result = new ArrayList<>();
        int len = sentence.length();
        for (int i = 0; i < len; i += size) {
            result.add(sentence.substring(i, Math.min(i + size, len)));
        }
        return result;
    }
}
