package com.example.milestone.service;

import com.example.milestone.auth.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 按调用者角色裁剪商业敏感字段：验证机构只看证据与状态，不看金额与账户。 */
public final class FieldTrimmer {
    private FieldTrimmer() {}

    /** 里程碑/快照视图：对 VERIFIER 移除 commercial 块。 */
    public static void trimView(ObjectNode view, Role role) {
        if (role == Role.VERIFIER) {
            view.remove("commercial");
        }
    }

    /** 时间线事件：对 VERIFIER 移除 payload 内的 commercial 子字段。 */
    public static void trimTimelineEvent(ObjectNode event, Role role) {
        if (role == Role.VERIFIER) {
            JsonNode payload = event.get("payload");
            if (payload instanceof ObjectNode p) {
                p.remove("commercial");
            }
        }
    }
}
