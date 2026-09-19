package com.example.milestone;

import com.example.milestone.support.Fixture;
import com.example.milestone.support.TestEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 审批流程的顺序行为：证据不齐拒绝放款、重复审批拒绝、已放款拒绝。 */
class ApprovalFlowIT {

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
    void finalApprovalRequiresCompleteEvidence() throws Exception {
        var f = Fixture.create(env);
        // 只提交两条证据，缺多方签收
        f.submitEvidence(env, f.rightsId, f.rightsKeys, f.rightsCertId,
                "OWNERSHIP_CONFIRMATION", com.example.milestone.json.Json.obj().put("patentNo", "ZL-9"), "inc-ev-1");
        f.submitEvidence(env, f.ownerId, f.ownerKeys, f.ownerCertId,
                "PROTOTYPE_EVIDENCE", com.example.milestone.json.Json.obj().put("prototypeSerial", "P-9"), "inc-ev-2");

        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "inc-r-1").status());
        var blocked = f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "inc-v-1");
        assertEquals(409, blocked.status());
        assertEquals("EVIDENCE_INCOMPLETE", blocked.errorCode());

        // 回滚干净：审批未记录、无放款；补齐证据后同一幂等键重试成功
        assertEquals(1, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
        assertEquals(0, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));

        f.submitEvidence(env, f.verifierId, f.verifierKeys, f.verifierCertId,
                "ACCEPTANCE_SIGNOFF", com.example.milestone.json.Json.obj().put("report", "A-9"), "inc-ev-3");
        // 同一幂等键 + 同一请求体重试 -> 重新执行并放款
        var retry = env.post("/api/milestones/" + f.milestoneId + "/approvals",
                f.approvalBody(f.verifierId, f.verifierKeys, f.verifierCertId,
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()),
                f.verifierId, "inc-v-1-retry");
        assertEquals(201, retry.status(), () -> String.valueOf(retry.json()));
        assertEquals("PAID", retry.json().get("status").asText());
        assertEquals(1, env.count(
                "SELECT count(*) FROM payment_instruction WHERE milestone_id = '" + f.milestoneId + "'"));
    }

    @Test
    void duplicateApprovalBySamePartyRejected() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "dup-r-1").status());
        // 同一参与方用不同幂等键再次审批 -> 冲突
        var dup = f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "dup-r-2");
        assertEquals(409, dup.status());
        assertEquals("ALREADY_APPROVED", dup.errorCode());
        assertEquals(1, env.count(
                "SELECT count(*) FROM milestone_approval WHERE milestone_id = '" + f.milestoneId + "'"));
    }

    @Test
    void approvalAfterPaidRejected() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        assertEquals(201, f.approve(env, f.rightsId, f.rightsKeys, f.rightsCertId, "paid-r-1").status());
        assertEquals(201, f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "paid-v-1").status());
        var late = f.approve(env, f.verifierId, f.verifierKeys, f.verifierCertId, "paid-v-2");
        assertEquals(409, late.status());
        assertEquals("MILESTONE_ALREADY_PAID", late.errorCode());
    }

    @Test
    void approvalRequiresPermissionAndRole() throws Exception {
        var f = Fixture.create(env);
        f.submitAllEvidence(env);
        // 项目方有 APPROVE_MILESTONE 权限，但其角色不在 requiredApproverRoles 中
        var wrongRole = f.approve(env, f.ownerId, f.ownerKeys, f.ownerCertId, "role-o-1");
        assertEquals(403, wrongRole.status());
    }
}
