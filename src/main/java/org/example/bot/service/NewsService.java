package org.example.bot.service;

/**
 * 新闻服务接口 — 获取当日新闻。
 */
public interface NewsService {

    /** 获取新闻列表 */
    String getNews(String category, int count);

    /**
     * 获取上一次查询中第 index 条新闻的详情（1-based）。
     * @return 格式化的单条新闻，或 null 表示索引无效
     */
    String getArticleDetail(int index);

    /** 是否可用 */
    boolean isAvailable();
}
