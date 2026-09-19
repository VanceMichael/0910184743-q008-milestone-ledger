package com.example.milestone;

import com.example.milestone.json.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 规范化 JSON 与哈希的单元测试。 */
class CanonicalJsonTest {

    @Test
    void canonicalizesKeyOrderAndWhitespace() {
        var a = Json.parse("{\"b\":1,\"a\":{\"d\":[2,{\"c\":\"x\"}],\"b\":true}}");
        var b = Json.parse("{ \"a\" : { \"b\" : true, \"d\" : [ 2, { \"c\" : \"x\" } ] }, \"b\" : 1 }");
        assertEquals("{\"a\":{\"b\":true,\"d\":[2,{\"c\":\"x\"}]},\"b\":1}", Json.canonical(a));
        assertEquals(Json.canonical(a), Json.canonical(b));
    }

    @Test
    void canonicalizesStringsWithEscaping() {
        var node = Json.parse("{\"k\":\"他\\\"x\\n\"}");
        assertEquals("{\"k\":\"他\\\"x\\n\"}", Json.canonical(node));
    }

    @Test
    void sha256MatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Json.sha256Hex(""));
    }
}
