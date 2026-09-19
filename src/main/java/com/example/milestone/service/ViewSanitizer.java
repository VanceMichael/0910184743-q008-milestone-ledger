package com.example.milestone.service;

import com.example.milestone.store.LedgerStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 按调用者裁剪商业字段：没有 VIEW_COMMERCIAL 权限（或未表明身份）的调用者
 * 看不到金额与证据 payload 中的 "commercial" 子树。
 */
public final class ViewSanitizer {

    private final LedgerStore store;

    public ViewSanitizer(LedgerStore store) {
        this.store = store;
    }

    public boolean canViewCommercial(Connection conn, UUID projectId, UUID callerPartyId) throws SQLException {
        return callerPartyId != null && store.hasPermission(conn, projectId, callerPartyId, "VIEW_COMMERCIAL");
    }

    /** 裁剪证据 payload：无商业权限时移除 "commercial" 键。 */
    public JsonNode trimPayload(JsonNode payload, boolean commercial) {
        if (commercial || payload == null || !payload.isObject() || !payload.has("commercial")) {
            return payload;
        }
        var copy = (ObjectNode) payload.deepCopy();
        copy.remove("commercial");
        return copy;
    }
}
