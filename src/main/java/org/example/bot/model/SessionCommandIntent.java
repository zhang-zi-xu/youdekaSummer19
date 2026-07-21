package org.example.bot.model;

/**
 * 会话管理命令意图 — 由 AI 分析用户消息后返回。
 *
 * @param action   操作类型：create / switch / delete / list / none
 * @param name     目标会话名称
 */
public record SessionCommandIntent(String action, String name) {

    public static SessionCommandIntent none() {
        return new SessionCommandIntent("none", null);
    }

    public boolean isCreate() { return "create".equals(action); }
    public boolean isSwitch() { return "switch".equals(action); }
    public boolean isDelete() { return "delete".equals(action); }
    public boolean isList()   { return "list".equals(action); }
    public boolean isNone()   { return "none".equals(action); }
}
