package org.example.bot.impl;

import org.example.bot.service.NewsService;
import org.example.bot.util.ConfigUtil;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新闻服务 — 支持 RSS 源和 JSON API 两种后端，完全可配置。
 *
 * <p>默认使用 RSSHub（国内可访问），也可在 config.properties 中自定：
 * <pre>{@code
 * # 可选：覆盖单个类别的 RSS 地址
 * news.rss.综合=https://your-rss-source/feed.xml
 * news.rss.科技=https://your-rss-source/tech.xml
 * }</pre>
 */
public class SimpleNewsServiceImpl implements NewsService {

    /** 预置的 RSS 新闻源 — 国内直连 */
    private static final Map<String, String> PRESET_RSS = new LinkedHashMap<>();
    static {
        PRESET_RSS.put("综合", "http://www.people.com.cn/rss/politics.xml");
        PRESET_RSS.put("科技", "http://www.people.com.cn/rss/tech.xml");
        PRESET_RSS.put("财经", "http://www.people.com.cn/rss/finance.xml");
        PRESET_RSS.put("体育", "http://www.people.com.cn/rss/sports.xml");
        PRESET_RSS.put("娱乐", "http://www.people.com.cn/rss/ent.xml");
    }

    private static final int DEFAULT_COUNT = 8;
    private static final int MAX_COUNT = 15;

    private final HttpClient http;
    private List<Article> lastArticles = List.of(); // 缓存最近一次查询结果

    public SimpleNewsServiceImpl() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String getNews(String category, int count) {
        if (count <= 0 || count > MAX_COUNT) count = DEFAULT_COUNT;

        // 优先使用用户配置的 RSS
        String confUrl = ConfigUtil.get("news.rss." + category, null);
        if (confUrl != null && !confUrl.isBlank()) {
            return fetchAndFormat(confUrl, category, count);
        }

        // 从预置列表中查找
        String key = category != null && !category.isBlank() ? category : "综合";
        String url = findUrl(key);
        if (url == null) {
            return "未找到「" + category + "」类别的新闻源。可用类别：" + String.join("、", PRESET_RSS.keySet())
                + "\n\n💡 可在 config.properties 中配置自定义 RSS：news.rss.综合=https://...";
        }

        return fetchAndFormat(url, category != null ? category : "综合", count);
    }

    @Override
    public String getArticleDetail(int index) {
        if (index < 1 || index > lastArticles.size()) {
            return "第" + index + "条新闻不存在，当前列表共" + lastArticles.size() + "条。";
        }
        return formatArticle(index, lastArticles.get(index - 1));
    }

    /**
     * 根据标题关键词模糊匹配新闻。
     * @param query 标题关键词
     * @return 匹配到的新闻详情，或未匹配的提示
     */
    public String findArticle(String query) {
        if (query == null || query.isBlank()) return "请提供新闻标题关键词。";
        // 精确匹配
        for (int i = 0; i < lastArticles.size(); i++) {
            Article a = lastArticles.get(i);
            if (a.title() != null && a.title().contains(query)) {
                return formatArticle(i + 1, a);
            }
        }
        // 模糊匹配
        for (int i = 0; i < lastArticles.size(); i++) {
            Article a = lastArticles.get(i);
            if (a.title() != null && a.desc() != null && a.desc().contains(query)) {
                return formatArticle(i + 1, a);
            }
        }
        return "未找到包含「" + query + "」的新闻。当前列表共" + lastArticles.size() + "条。";
    }

    private String formatArticle(int index, Article a) {
        StringBuilder sb = new StringBuilder();
        sb.append("📰 第").append(index).append("条：").append(a.title()).append("\n\n");
        if (a.desc() != null && !a.desc().isBlank()) {
            sb.append(a.desc()).append("\n\n");
        } else {
            sb.append("（该新闻仅有标题，暂无详细内容）\n\n");
        }
        if (a.link() != null && !a.link().isBlank()) {
            sb.append("🔗 原文链接：").append(a.link());
        }
        sb.append("\n\n⚠️ 你只有标题和摘要，禁止编造正文内容。");
        return sb.toString();
    }

    private String findUrl(String key) {
        String url = PRESET_RSS.get(key);
        if (url != null) return url;
        // 模糊匹配
        for (String k : PRESET_RSS.keySet()) {
            if (k.contains(key) || key.contains(k)) return PRESET_RSS.get(k);
        }
        return null;
    }

    private String fetchAndFormat(String url, String category, int count) {
        try {
            String body = fetch(url);
            List<Article> articles = parseRss(body, count);
            this.lastArticles = articles; // 缓存供后续 getArticleDetail 使用
            if (articles.isEmpty()) return "暂时没有获取到新闻，请稍后再试。";
            return format(articles, category);
        } catch (Exception e) {
            System.err.println("[News] ❌ " + url + " → " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                return "新闻源连接超时（" + url + "）。\n\n"
                    + "💡 你可以在 config.properties 中配置自建的新闻 RSS 源：\n"
                    + "news.rss.综合=https://你的RSS地址/feed.xml";
            }
            return "获取新闻失败：" + e.getMessage();
        }
    }

    private String fetch(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    /** 解析 RSS XML，提取 title + link + description */
    private List<Article> parseRss(String xml, int count) {
        List<Article> result = new ArrayList<>();
        try {
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            int offset = (bytes.length > 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) ? 3 : 0;
            InputStream is = new ByteArrayInputStream(bytes, offset, bytes.length - offset);
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
            var items = doc.getElementsByTagName("item");
            for (int i = 0; i < Math.min(items.getLength(), count); i++) {
                var item = items.item(i);
                String title = textOf(item, "title");
                String link = textOf(item, "link");
                String desc = textOf(item, "description");
                if (title != null && !title.isBlank()) {
                    result.add(new Article(title, link, desc));
                }
            }
        } catch (Exception e) {
            System.err.println("[News] ⚠ RSS 解析失败: " + e.getMessage());
        }
        return result;
    }

    private static String textOf(org.w3c.dom.Node parent, String tagName) {
        var list = ((org.w3c.dom.Element) parent).getElementsByTagName(tagName);
        if (list.getLength() > 0) {
            String text = list.item(0).getTextContent();
            return text != null ? text.strip() : null;
        }
        return null;
    }

    private String format(List<Article> articles, String category) {
        String label = (category == null || category.isBlank()) ? "综合" : category;
        StringBuilder sb = new StringBuilder("📰 ").append(label).append("新闻：\n");
        for (int i = 0; i < articles.size(); i++) {
            Article a = articles.get(i);
            sb.append(i + 1).append(". ").append(a.title);
            // 只附带极短摘要，避免微信端排版混乱
            if (a.desc != null && !a.desc.isBlank() && a.desc.length() < 30) {
                sb.append(" — ").append(a.desc);
            }
            sb.append("\n");
        }
        sb.append("\n⚠️ 原样列出以上全部新闻（含序号），不要用 Markdown，不要换序。用户追问时调用 read_news_article 传标题关键词。");
        return sb.toString();
    }

    private record Article(String title, String link, String desc) {
        Article {
            if (desc != null) {
                // 清理 RSS 描述中的 HTML 标签，防止微信端排版错乱
                desc = desc.replaceAll("<[^>]+>", "").replaceAll("&\\w+;", "").replaceAll("\\s+", " ").strip();
            }
        }
    }
}
