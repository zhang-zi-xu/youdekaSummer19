package org.example.bot.impl;

import org.example.bot.service.DateTimeService;
import org.example.bot.util.ConfigUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 日期时间查询工具 — 独立的 Function Calling 工具，由 AI 自主决定何时调用。
 *
 * <h3>默认数据源：Kiprio Timezone API</h3>
 * <pre>
 *   端点：GET https://kiprio.com/v1/timezone/lookup?city={城市或IANA时区}
 *   认证：请求头 Authorization: Bearer &lt;key&gt;
 * </pre>
 * 说明：{@code city} 参数同时接受英文城市名（Tokyo、New York）与 IANA 时区名
 * （Asia/Shanghai）；免费额度 500 次/天，注册 https://kiprio.com/signup 获取 key。
 *
 * <h3>配置（优先级见 {@link ConfigUtil}）</h3>
 * <ul>
 *   <li>{@code datetime.api.key} / 环境变量 {@code DATETIME_API_KEY}
 *       — Kiprio API 密钥（使用默认 Kiprio 时必须配置）</li>
 *   <li>{@code datetime.api.url} / 环境变量 {@code DATETIME_API_URL}
 *       — 自定义日期时间 API 地址（可选；填了就走自定义 ?tz=xxx&amp;apikey=xxx 逻辑）</li>
 * </ul>
 *
 * 无论用哪个 API，最终都把返回的 JSON 原样交给 LLM，由它转成自然语言回复用户。
 */
public class DateTimeServiceImpl implements DateTimeService {

    /** 默认 Kiprio 时区查询端点（city 必填，接受城市名或 IANA 时区） */
    private static final String KIPRIO_LOOKUP = "https://kiprio.com/v1/timezone/lookup";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 常见城市/国家名 → IANA 时区（让 LLM 传「纽约」「东京」也能命中；Kiprio 接受 IANA） */
    private static final Map<String, String> CITY_TO_IANA = new HashMap<>();
    static {
        CITY_TO_IANA.put("北京", "Asia/Shanghai");
        CITY_TO_IANA.put("中国", "Asia/Shanghai");
        CITY_TO_IANA.put("大陆", "Asia/Shanghai");
        CITY_TO_IANA.put("上海", "Asia/Shanghai");
        CITY_TO_IANA.put("香港", "Asia/Hong_Kong");
        CITY_TO_IANA.put("台北", "Asia/Taipei");
        CITY_TO_IANA.put("台湾", "Asia/Taipei");
        CITY_TO_IANA.put("东京", "Asia/Tokyo");
        CITY_TO_IANA.put("日本", "Asia/Tokyo");
        CITY_TO_IANA.put("首尔", "Asia/Seoul");
        CITY_TO_IANA.put("汉城", "Asia/Seoul");
        CITY_TO_IANA.put("韩国", "Asia/Seoul");
        CITY_TO_IANA.put("新加坡", "Asia/Singapore");
        CITY_TO_IANA.put("迪拜", "Asia/Dubai");
        CITY_TO_IANA.put("阿联酋", "Asia/Dubai");
        CITY_TO_IANA.put("孟买", "Asia/Kolkata");
        CITY_TO_IANA.put("印度", "Asia/Kolkata");
        CITY_TO_IANA.put("曼谷", "Asia/Bangkok");
        CITY_TO_IANA.put("纽约", "America/New_York");
        CITY_TO_IANA.put("华盛顿", "America/New_York");
        CITY_TO_IANA.put("美东", "America/New_York");
        CITY_TO_IANA.put("洛杉矶", "America/Los_Angeles");
        CITY_TO_IANA.put("旧金山", "America/Los_Angeles");
        CITY_TO_IANA.put("西雅图", "America/Los_Angeles");
        CITY_TO_IANA.put("美西", "America/Los_Angeles");
        CITY_TO_IANA.put("芝加哥", "America/Chicago");
        CITY_TO_IANA.put("伦敦", "Europe/London");
        CITY_TO_IANA.put("英国", "Europe/London");
        CITY_TO_IANA.put("英格兰", "Europe/London");
        CITY_TO_IANA.put("巴黎", "Europe/Paris");
        CITY_TO_IANA.put("法国", "Europe/Paris");
        CITY_TO_IANA.put("柏林", "Europe/Berlin");
        CITY_TO_IANA.put("德国", "Europe/Berlin");
        CITY_TO_IANA.put("莫斯科", "Europe/Moscow");
        CITY_TO_IANA.put("俄罗斯", "Europe/Moscow");
        CITY_TO_IANA.put("悉尼", "Australia/Sydney");
        CITY_TO_IANA.put("墨尔本", "Australia/Melbourne");
        CITY_TO_IANA.put("澳洲", "Australia/Sydney");
        CITY_TO_IANA.put("澳大利亚", "Australia/Sydney");
    }

    /**
     * 查询指定时区/城市的当前日期时间。
     *
     * @param timezone 时区或城市，例如 "Asia/Shanghai"、"纽约"、"东京"
     * @return API 返回的原始 JSON 文本（交由 LLM 转自然语言）；出错时返回中文错误信息
     */
    @Override
    public String query(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Shanghai";
        }

        String customUrl = ConfigUtil.get("datetime.api.url", "DATETIME_API_URL");
        String key = ConfigUtil.get("datetime.api.key", "DATETIME_API_KEY");

        if (customUrl != null && !customUrl.isBlank()) {
            // 自定义 API：沿用 ?tz=xxx&apikey=xxx 的拼接（兼容用户自己的服务）
            String sep = customUrl.contains("?") ? "&" : "?";
            String url = customUrl + sep + "tz=" + enc(timezone)
                    + (key != null ? "&apikey=" + enc(key) : "");
            return doGet(url, null);
        }

        // 默认走 Kiprio：city 参数接受城市名或 IANA，认证用 Bearer
        if (key == null || key.isBlank()) {
            return "日期时间 API 未配置密钥：请在 config.properties 填入 datetime.api.key"
                    + "（免费注册 https://kiprio.com/signup 获取，500 次/天）";
        }
        String city = toIana(timezone);
        String url = KIPRIO_LOOKUP + "?city=" + enc(city);
        return doGet(url, key);
    }

    /** 执行 GET；bearer 非空时带上 Authorization: Bearer 请求头 */
    private String doGet(String url, String bearer) {
        System.out.println("[时间] 查询: " + url + (bearer != null ? " (Bearer)" : ""));
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET();
            if (bearer != null && !bearer.isBlank()) {
                b.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "日期时间 API 返回错误: HTTP " + resp.statusCode()
                        + (resp.statusCode() == 401 ? "（密钥无效或已过期）" : "");
            }
            return resp.body();
        } catch (Exception e) {
            return "日期时间查询失败: " + e.getMessage();
        }
    }

    /** 把用户/LLM 传入的名称转成 IANA 时区；已是 IANA（含 /）或查不到则原样返回 */
    private static String toIana(String raw) {
        if (raw.contains("/")) {
            return raw;                           // 已经是 IANA，如 Asia/Shanghai
        }
        String hit = CITY_TO_IANA.get(raw.trim());
        if (hit != null) {
            return hit;
        }
        for (Map.Entry<String, String> e : CITY_TO_IANA.entrySet()) {
            if (e.getKey().equalsIgnoreCase(raw.trim())) {
                return e.getValue();              // 英文城市名兜底
            }
        }
        return raw;                               // 直接把原始名交给 Kiprio（它也认英文城市名）
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
