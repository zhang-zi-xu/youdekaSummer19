package org.example.bot.service;

import java.util.List;

/**
 * 新闻服务接口 — 获取真实新闻，不依赖任何 API Key（使用 RSS 源）。
 */
public interface NewsService {

    /**
     * 获取新闻列表。
     *
     * @param category 类别：综合、科技、财经、体育、娱乐
     * @param count    返回条数
     * @return 格式化后的新闻列表文本
     */
    String getNews(String category, int count);

    /**
     * 根据关键词从最近一次查询结果中查找某条新闻的详情。
     *
     * @param query 标题关键词或序号
     * @return 该新闻的详细信息，找不到则返回提示
     */
    String getArticleDetail(String query);

    /**
     * 返回最近一次查询的原始条目列表（用于缓存）。
     */
    List<NewsItem> getLastResults();

    /** 新闻服务始终可用（无需 API Key） */
    default boolean isAvailable() { return true; }

    /** 新闻条目 — 供缓存使用 */
    record NewsItem(String title, String description, String link) {}
}
