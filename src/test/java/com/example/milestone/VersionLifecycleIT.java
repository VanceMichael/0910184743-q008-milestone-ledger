package com.example.milestone;

import com.example.milestone.json.Json;
import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 版本生命周期：过期版本只能归档，不能写入证据或推进审批；版本差异查询。 */
class VersionLifecycleIT {

    private static TestEnv env;

    @BeforeAll
    static void up() throws Exception {
        env = TestEnv.start();
    }

    @AfterAll
    static void down() {
        env.close();
    }

    @Test
    void expiredVersionOnlyArchivesAndDiffReportsChanges() throws Exception {
        var f = Fixture.create(env);
        f.submitEvidence(env, f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", Json.obj().put("patentNo", "ZL-V1"), "vl-ev-1");
        String v1 = f.versionId.toString();

        // 开启 v2 -> v1 过期
        var v2Resp = env.post("/api/projects/" + f.projectId + "/versions", Json.obj(), null, null);
        assertEquals(201, v2Resp.status(), () -> String.valueOf(v2Resp.json()));
        String v2 = v2Resp.json().get("id").asText();
        assertEquals(2, v2Resp.json().get("versionNo").asInt());

        // 过期版本：拒绝新证据
        var evExpired = env.post("/api/evidence-events",
                f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId, "OWNERSHIP_CONFIRMATION",
                        Json.obj().put("patentNo", "ZL-V1-LATE"),
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()),
                f.rightsId, "vl-ev-late");
        assertEquals(409, evExpired.status());
        assertEquals("VERSION_NOT_CURRENT", evExpired.errorCode());

        // 过期版本：拒绝审批推进
        var approveExpired = f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "vl-ap-1");
        assertEquals(409, approveExpired.status());
        assertEquals("VERSION_NOT_CURRENT", approveExpired.errorCode());

        // CURRENT 版本不能归档
        var archiveCurrent = env.post("/api/projects/" + f.projectId + "/versions/" + v2 + "/archive",
                Json.obj(), null, null);
        assertEquals(409, archiveCurrent.status());
        assertEquals("VERSION_NOT_EXPIRED", archiveCurrent.errorCode());

        // 过期版本可以归档；重复归档冲突
        var archived = env.post("/api/projects/" + f.projectId + "/versions/" + v1 + "/archive",
                Json.obj(), null, null);
        assertEquals(200, archived.status(), () -> String.valueOf(archived.json()));
        assertEquals("ARCHIVED", archived.json().get("status").asText());
        var archiveAgain = env.post("/api/projects/" + f.projectId + "/versions/" + v1 + "/archive",
                Json.obj(), null, null);
        assertEquals(409, archiveAgain.status());
        assertEquals("VERSION_ALREADY_ARCHIVED", archiveAgain.errorCode());

        // 归档版本同样拒绝写入
        var evArchived = env.post("/api/evidence-events",
                f.evidenceBody(f.rightsId, f.rightsKeys, f.rightsCertId, "OWNERSHIP_CONFIRMATION",
                        Json.obj().put("patentNo", "ZL-V1-ARCH"),
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()),
                f.rightsId, "vl-ev-arch");
        assertEquals(409, evArchived.status());
        assertEquals("VERSION_NOT_CURRENT", evArchived.errorCode());

        // v2 上建里程碑：MS-1 改了标题与金额，新增 MS-2
        var ms1 = env.post("/api/projects/" + f.projectId + "/milestones", Json.obj()
                .put("code", "MS-1").put("title", "样机交付与验收（修订）")
                .put("amount", "1350000.00").put("currency", "CNY"), null, null);
        assertEquals(201, ms1.status(), () -> String.valueOf(ms1.json()));
        var ms2 = env.post("/api/projects/" + f.projectId + "/milestones", Json.obj()
                .put("code", "MS-2").put("title", "量产准备")
                .put("amount", "800000.00").put("currency", "CNY"), null, null);
        assertEquals(201, ms2.status(), () -> String.valueOf(ms2.json()));

        // 版本差异（商业权限视角）
        var diff = env.get("/api/projects/" + f.projectId + "/versions/diff?from=" + v1 + "&to=" + v2,
                f.ownerId);
        assertEquals(200, diff.status(), () -> String.valueOf(diff.json()));
        var milestones = diff.json().get("milestones");
        assertEquals(1, milestones.get("added").size());
        assertEquals("MS-2", milestones.get("added").get(0).get("code").asText());
        assertEquals(0, milestones.get("removed").size());
        assertEquals(1, milestones.get("changed").size());
        var changed = milestones.get("changed").get(0);
        assertEquals("MS-1", changed.get("code").asText());
        assertTrue(changed.get("fields").has("title"));
        assertTrue(changed.get("fields").has("amount"), "商业权限调用者应看到金额变化");
        assertEquals("1200000.00", changed.get("fields").get("amount").get("from").asText());
        assertEquals("1350000.00", changed.get("fields").get("amount").get("to").asText());
        assertEquals(1, diff.json().get("evidenceCounts").get("from").asInt());
        assertEquals(0, diff.json().get("evidenceCounts").get("to").asInt());

        // 版本差异（匿名视角）：金额变化被裁剪
        var diffAnon = env.get("/api/projects/" + f.projectId + "/versions/diff?from=" + v1 + "&to=" + v2,
                null);
        var changedAnon = diffAnon.json().get("milestones").get("changed").get(0);
        assertFalse(changedAnon.get("fields").has("amount"), "匿名调用者不应看到金额变化");
        assertTrue(changedAnon.get("fields").has("title"));
    }
}
