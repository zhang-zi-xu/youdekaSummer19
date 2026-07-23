package org.example.bot.service;

public interface DateTimeService {
    /**
     * 查询指定时区/城市的当前日期时间。
     * @param timezone 时区或城市，例如 "Asia/Shanghai"、"纽约"、"东京"
     * @return API 返回的原始 JSON 文本（交由 LLM 转自然语言）；出错时返回中文错误信息
     */
    String query(String timezone);
}
