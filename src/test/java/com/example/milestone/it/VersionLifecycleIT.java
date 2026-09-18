package com.example.milestone.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 版本生命周期：过期版本只能归档，不能推进当前项目；版本差异可查。 */
class VersionLifecycleIT extends ITBase {

    @Test
    @DisplayName("过期版本拒绝证据与审批，只能归档；差异查询正确")
    void expiredVersionOnlyArchives() {
        var f = newStandardProject();

        // v1 上先提交一类证据
        var e1 = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "IP_ASSIGNMENT", "ev-v1-" + System.nanoTime());
        assertEquals(201, e1.status());

        // 项目方激活 v2（v1 自动过期）
        var v2 = alice.createVersion(f.projectId(), LedgerClient.milestoneDef("M1",
                new String[] {"IP_ASSIGNMENT", "ACCEPTANCE_REPORT"},
                new String[] {"RIGHTS_HOLDER", "VERIFIER"}, "320000.00"));
        assertEquals(201, v2.status(), "新版本激活失败: " + v2.body());
        String v2Id = v2.body().get("versionId").asText();
        String v2Milestone = v2.body().get("milestones").get(0).get("id").asText();
        assertEquals(v1Id(v2), f.versionId(), "激活新版本应过期旧版本");

        // 过期版本：证据提交 → 409 VERSION_EXPIRED，且同键重试回放同一决定
        String key = "ev-expired-" + System.nanoTime();
        var rejected = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", key);
        assertEquals(409, rejected.status());
        assertEquals("VERSION_EXPIRED", rejected.errorCode());
        var replayed = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", key);
        assertTrue(replayed.replay(), "拒绝决定也应被幂等回放");
        assertEquals(409, replayed.status());
        assertEquals(rejected.body().toString(), replayed.body().toString());

        // 过期版本：审批 → 409 VERSION_NOT_ACTIVE
        var lateApproval = bob.approve(f.projectId(), f.milestoneId(), "ap-expired-" + System.nanoTime());
        assertEquals(409, lateApproval.status());
        assertEquals("VERSION_NOT_ACTIVE", lateApproval.errorCode());

        // 归档：ACTIVE 版本不能归档；过期版本可以；归档后只读
        var prematureArchive = alice.archiveVersion(f.projectId(), v2Id);
        assertEquals(409, prematureArchive.status());
        assertEquals("VERSION_NOT_EXPIRED", prematureArchive.errorCode());
        var archived = alice.archiveVersion(f.projectId(), f.versionId());
        assertEquals(200, archived.status());
        assertEquals("ARCHIVED", archived.body().get("status").asText());
        var onArchived = alice.submitEvidence(f.projectId(), f.versionId(), f.milestoneId(),
                "PROTOTYPE_DEMO", "ev-archived-" + System.nanoTime());
        assertEquals(409, onArchived.status());
        assertEquals("VERSION_ARCHIVED", onArchived.errorCode());
        var rearchive = alice.archiveVersion(f.projectId(), f.versionId());
        assertEquals(409, rearchive.status());
        assertEquals("ALREADY_ARCHIVED", rearchive.errorCode());

        // 当前版本正常推进
        var ok = alice.submitEvidence(f.projectId(), v2Id, v2Milestone,
                "ACCEPTANCE_REPORT", "ev-v2-" + System.nanoTime());
        assertEquals(201, ok.status());

        // 版本差异：v2 新增 ACCEPTANCE_REPORT，v1 的 IP_ASSIGNMENT 未带入 v2
        var diff = dave.diff(f.projectId(), 1, 2);
        assertEquals(200, diff.status());
        assertTrue(diff.body().at("/evidence/addedInTo").toString().contains("ACCEPTANCE_REPORT"));
        assertTrue(diff.body().at("/evidence/removedInTo").toString().contains("IP_ASSIGNMENT"));
        var milestones = diff.body().get("milestones");
        assertEquals(1, milestones.size());
        assertEquals("M1", milestones.get(0).get("code").asText());
        assertEquals("IN_REVIEW", milestones.get(0).get("fromStatus").asText());
        assertEquals("IN_REVIEW", milestones.get(0).get("toStatus").asText());
        assertFalse(milestones.get(0).get("changed").asBoolean());
    }

    private static String v1Id(LedgerClient.Result v2) {
        return v2.body().get("expiredVersionId").asText();
    }
}
