package com.miniagent.tool.impl;

import com.miniagent.util.Texts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地知识库：把 {@code docs/knowledge/*.md} 读进内存，按 {@code ## } 标题切段，供 search / read_docs 使用。
 *
 * <p>检索是「可控的 mock」：不联网、结果确定，但用的是真实的关键词打分（title 命中加权 + 短语命中加权 +
 * 长度归一），因此能真实反映「模型拿到检索片段后怎么用」这一段链路。
 */
public final class KnowledgeBase {

    /** 一篇文档。 */
    public record Document(String id, String title, Path path, String content) {

        public List<String> lines() {
            return content.lines().toList();
        }
    }

    /** 文档里的一个段落（按 ## 标题切分）。 */
    public record Section(String docId, String docTitle, String heading, String content) {
    }

    /** 一条检索命中。 */
    public record Hit(Section section, double score, String snippet) {
    }

    private final Path root;
    private final Map<String, Document> documents = new LinkedHashMap<>();
    private final List<Section> sections = new ArrayList<>();

    private KnowledgeBase(Path root) {
        this.root = root;
    }

    /** 从目录加载（目录不存在时返回空知识库，不抛异常，保证 Agent 仍可用）。 */
    public static KnowledgeBase load(Path dir) {
        KnowledgeBase base = new KnowledgeBase(dir);
        if (dir == null || !Files.isDirectory(dir)) {
            return base;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .sorted()
                    .forEach(base::index);
        } catch (IOException e) {
            throw new UncheckedIOException("读取知识库目录失败: " + dir, e);
        }
        return base;
    }

    /** 默认知识库位置：工作目录下的 docs/knowledge。 */
    public static KnowledgeBase loadDefault() {
        return load(Path.of("docs", "knowledge"));
    }

    private void index(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        String fileName = file.getFileName().toString();
        String id = fileName.replaceAll("\\.(md|txt)$", "");
        String title = content.lines()
                .filter(line -> line.startsWith("# "))
                .findFirst()
                .map(line -> line.substring(2).strip())
                .orElse(id);
        Document document = new Document(id, title, file, content);
        documents.put(id.toLowerCase(Locale.ROOT), document);

        // 按 ## 切段：第一段是标题之前的内容（通常是导语）
        String[] blocks = content.split("(?m)^##\\s+");
        String preamble = blocks.length > 0 ? blocks[0] : content;
        if (!preamble.strip().isEmpty()) {
            sections.add(new Section(id, title, title, preamble.strip()));
        }
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int newline = block.indexOf('\n');
            String heading = newline < 0 ? block.strip() : block.substring(0, newline).strip();
            String body = newline < 0 ? "" : block.substring(newline + 1).strip();
            sections.add(new Section(id, title, heading, body));
        }
    }

    public List<Document> documents() {
        return List.copyOf(documents.values());
    }

    public List<String> documentIds() {
        return documents.values().stream().map(Document::id).toList();
    }

    public int sectionCount() {
        return sections.size();
    }

    public Path root() {
        return root;
    }

    /** 按 id 查文档，支持大小写不敏感与带扩展名。 */
    public Optional<Document> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.strip().toLowerCase(Locale.ROOT).replaceAll("\\.(md|txt)$", "");
        Document exact = documents.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        return documents.values().stream()
                .filter(d -> d.id().toLowerCase(Locale.ROOT).contains(key) && !key.isEmpty())
                .findFirst();
    }

    /** 关键词检索。 */
    public List<Hit> search(String query, int topK) {
        List<String> queryTokens = Texts.tokenize(query == null ? "" : query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        String phrase = query.strip();
        List<Hit> hits = new ArrayList<>();
        for (Section section : sections) {
            String haystack = section.heading() + "\n" + section.content();
            var haystackTokens = Texts.tokenSet(haystack);
            double score = 0;
            int matched = 0;
            for (String token : queryTokens) {
                if (haystackTokens.contains(token)) {
                    matched++;
                    score += token.length() >= 2 ? 1.0 : 0.4;
                }
            }
            if (matched == 0) {
                continue;
            }
            if (!phrase.isEmpty() && haystack.contains(phrase)) {
                score += 3.0;
            }
            if (section.heading().contains(phrase) && !phrase.isEmpty()) {
                score += 1.5;
            }
            // 长度归一：太长的段落降低权重，避免「什么都沾一点」的长文霸榜
            score /= (1.0 + Math.log10(1.0 + haystack.length() / 400.0));
            hits.add(new Hit(section, round(score), snippet(section, queryTokens)));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    /** 摘出最相关的一段上下文（以命中行居中）。 */
    private String snippet(Section section, List<String> queryTokens) {
        List<String> lines = section.content().lines().toList();
        if (lines.isEmpty()) {
            return "";
        }
        int bestIndex = 0;
        double bestScore = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            double score = 0;
            for (String token : queryTokens) {
                if (line.contains(token)) {
                    score += 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        int from = Math.max(0, bestIndex - 2);
        int to = Math.min(lines.size(), bestIndex + 3);
        return String.join("\n", lines.subList(from, to)).strip();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
