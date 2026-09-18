package com.example.milestone.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void sortsObjectKeysRecursively() {
        var node = Json.parse("{\"b\":1,\"a\":{\"y\":2,\"x\":1},\"c\":[{\"z\":0,\"a\":1}]}");
        assertEquals("{\"a\":{\"x\":1,\"y\":2},\"b\":1,\"c\":[{\"a\":1,\"z\":0}]}",
                CanonicalJson.canonical(Json.parseCanonical(node.toString())));
    }

    @Test
    void preservesDecimalScaleLikeJsonb() {
        // 与 PostgreSQL jsonb 的规范化输出对齐：0.10 保持 0.10
        var node = Json.parseCanonical("{\"amount\":0.10,\"qty\":2}");
        assertEquals("{\"amount\":0.10,\"qty\":2}", CanonicalJson.canonical(node));
    }

    @Test
    void escapesControlCharacters() {
        var node = Json.parseCanonical("{\"s\":\"a\\nb\\t\\\"q\\\"\"}");
        assertEquals("{\"s\":\"a\\nb\\t\\\"q\\\"\"}", CanonicalJson.canonical(node));
    }

    @Test
    void arraysKeepOrder() {
        var node = Json.parseCanonical("{\"arr\":[3,1,2]}");
        assertEquals("{\"arr\":[3,1,2]}", CanonicalJson.canonical(node));
    }
}
