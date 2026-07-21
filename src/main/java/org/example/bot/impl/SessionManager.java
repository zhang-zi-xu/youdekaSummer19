package org.example.bot.impl;

import java.util.*;

/**
 * 多会话管理器 — 每个用户可以创建多个独立对话，互不影响。
 */
public class SessionManager {

    public static final int MAX_HISTORY = 200; // 100 轮对话

    // 帮助指南
    static final String HELP_MESSAGE = """
        🤖 我是你的微信AI助手，支持以下功能：

        💬 自然对话
        🎨 图片生成 — \"帮我画一只猫\"
        👁 图片识别 — 发送图片即可
        📄 文件总结 — 发送 TXT/PDF/Word/Excel 自动总结
        🌤 天气查询 — \"北京今天天气怎么样\"
        🎤 语音回复 — \"发语音告诉我\"（一次性）
        🔊 语音模式 — \"开启语音模式\" 后所有回复附带语音
        🎭 设定人设 — \"设定人设：你是一只猫娘\"
        🎵 切换音色 — \"切换音色 Ethan\" 切换 TTS 音色
        📂 多会话 — 下面这些命令可以管理对话：

        • 新建对话「名称」
        • 切换到「名称」对话
        • 查看所有对话
        • 删掉「名称」对话""";

    // userId → Map<sessionName, Session>
    private final Map<String, Map<String, Session>> sessions = new HashMap<>();
    // userId → current session name
    private final Map<String, String> currentSession = new HashMap<>();
    // userId → voice mode
    private final Map<String, Boolean> voiceMode = new HashMap<>();
    // 全局默认人设和技术指令
    private final String defaultPersona;
    private final String techInstructions;

    public SessionManager(String defaultPersona, String techInstructions) {
        this.defaultPersona = defaultPersona;
        this.techInstructions = techInstructions;
    }

    /** 构建完整 system prompt */
    String fullSystemPrompt(Session s) {
        return s.persona + "\n" + techInstructions;
    }

    /** 获取当前会话（没有则创建默认会话） */
    public synchronized Session getOrCreate(String userId) {
        String name = currentSession.get(userId);
        if (name == null) {
            name = "默认";
            currentSession.put(userId, name);
        }
        final String sessionName = name;
        Map<String, Session> userSessions = sessions.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        return userSessions.computeIfAbsent(sessionName, k -> new Session(sessionName, defaultPersona));
    }

    /** 创建新会话并切换过去 */
    public synchronized Session createSession(String userId, String name) {
        name = name.strip();
        if (name.isEmpty()) name = "未命名";

        Map<String, Session> userSessions = sessions.computeIfAbsent(userId, k -> new LinkedHashMap<>());
        // 确保默认会话始终存在
        if (!userSessions.containsKey("默认")) {
            userSessions.put("默认", new Session("默认", defaultPersona));
        }
        // 同名覆盖
        Session session = new Session(name, defaultPersona);
        userSessions.put(name, session);
        currentSession.put(userId, name);
        return session;
    }

    /** 切换到已有会话，不存在则创建 */
    public synchronized Session switchTo(String userId, String name) {
        name = name.strip();
        currentSession.put(userId, name);
        return getOrCreate(userId);
    }

    /** 删除会话，不允许删除最后一个 */
    public synchronized String deleteSession(String userId, String name) {
        name = name.strip();
        getOrCreate(userId);  // 确保默认存在
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.size() <= 1) {
            return "不能删除唯一的对话，至少保留一个。";
        }
        Session removed = userSessions.remove(name);
        if (removed == null) return "找不到对话「" + name + "」。";

        // 如果删除的是当前会话，切到第一个
        if (name.equals(currentSession.get(userId))) {
            String first = userSessions.keySet().iterator().next();
            currentSession.put(userId, first);
            return "已删除「" + name + "」，当前对话：「" + first + "」。";
        }
        return "已删除对话「" + name + "」。";
    }

    /** 列出所有会话 */
    public synchronized String listSessions(String userId) {
        // 确保至少有一个默认会话
        getOrCreate(userId);

        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null || userSessions.isEmpty()) return "你还没有任何对话。";

        String current = currentSession.get(userId);
        StringBuilder sb = new StringBuilder("📂 你的对话列表：\n");
        for (String name : userSessions.keySet()) {
            sb.append(name.equals(current) ? "  ● " : "  ○ ");
            sb.append(name);
            Session s = userSessions.get(name);
            sb.append("（").append(s.roles.size() / 2).append("轮）");
            if (name.equals(current)) sb.append(" ← 当前");
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    /** 获取对话历史长度，用于决定是否发帮助 */
    public synchronized int historySize(String userId) {
        Map<String, Session> userSessions = sessions.get(userId);
        if (userSessions == null) return 0;
        String name = currentSession.get(userId);
        if (name == null) return 0;
        Session s = userSessions.get(name);
        return s != null ? s.roles.size() : 0;
    }

    /** 清空当前会话（不删除） */
    public synchronized void clearCurrent(String userId) {
        Session s = getOrCreate(userId);
        s.clear();
    }

    /** 修改当前会话的人设 */
    public synchronized void setPersona(String userId, String persona) {
        Session s = getOrCreate(userId);
        s.persona = persona;
    }

    /** 切换语音模式 */
    public synchronized boolean toggleVoiceMode(String userId) {
        boolean current = voiceMode.getOrDefault(userId, false);
        voiceMode.put(userId, !current);
        return !current;
    }

    /** 查询语音模式是否开启 */
    public synchronized boolean isVoiceMode(String userId) {
        return voiceMode.getOrDefault(userId, false);
    }

    /** 获取所有 session 快照（用于恢复等） */
    public synchronized Map<String, Session> getAllSessions(String userId) {
        return sessions.getOrDefault(userId, Collections.emptyMap());
    }
}
