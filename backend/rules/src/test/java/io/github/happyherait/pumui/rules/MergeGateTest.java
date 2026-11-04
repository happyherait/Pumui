package io.github.happyherait.pumui.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MergeGateTest {
    private static final List<RequiredReview> LEAD_AND_EHS =
            List.of(new RequiredReview("TEAM_LEAD", List.of("always")), new RequiredReview("EHS", List.of("hazard")));

    private static ReviewRecord approve(String who, String role, String hash) {
        return new ReviewRecord(who, role, "APPROVED", hash);
    }

    private static MergeDecision run(List<CheckResult> checks, List<ReviewRecord> reviews) {
        return MergeGate.evaluate(checks, LEAD_AND_EHS, "kim", "h2", reviews);
    }

    @Test
    void allowsWhenEveryRoleApprovedCurrentRevision() {
        assertTrue(run(List.of(), List.of(approve("lee", "TEAM_LEAD", "h2"), approve("choi", "EHS", "h2"))).allowed());
    }

    @Test
    void staleApprovalDoesNotCount() {
        MergeDecision d = run(List.of(), List.of(approve("lee", "TEAM_LEAD", "h1"), approve("choi", "EHS", "h2")));
        assertFalse(d.allowed());
        assertEquals(List.of("TEAM_LEAD 리뷰 대기"), d.blockers());
    }

    @Test
    void requesterCannotApproveOwnRequest() {
        assertFalse(run(List.of(), List.of(approve("kim", "TEAM_LEAD", "h2"), approve("choi", "EHS", "h2"))).allowed());
    }

    @Test
    void onePersonCannotFillTwoRoles() {
        MergeDecision d = run(List.of(), List.of(approve("lee", "TEAM_LEAD", "h2"), approve("lee", "EHS", "h2")));
        assertFalse(d.allowed());
        assertTrue(d.blockers().get(0).contains("리뷰어 중복"));
    }

    @Test
    void changesRequestedBlocksUntilSameRoleApproves() {
        ReviewRecord change = new ReviewRecord("lee", "TEAM_LEAD", "CHANGES_REQUESTED", "h2");
        assertFalse(run(List.of(), List.of(change, approve("choi", "EHS", "h2"))).allowed());
        assertTrue(run(List.of(), List.of(change, approve("choi", "EHS", "h2"), approve("lee", "TEAM_LEAD", "h2"))).allowed());
    }

    @Test
    void failedCheckBlocksEvenWithApprovals() {
        CheckResult failed = new CheckResult("budget.available", "과제 예산", CheckStatus.FAIL, "초과");
        assertFalse(run(List.of(failed), List.of(approve("lee", "TEAM_LEAD", "h2"), approve("choi", "EHS", "h2"))).allowed());
    }

    @Test
    void hashIgnoresKeyOrderButNotValues() {
        assertEquals(RevisionHash.of(Map.of("a", "1", "b", "2")), RevisionHash.of(Map.of("b", "2", "a", "1")));
        assertFalse(RevisionHash.of(Map.of("a", "1")).equals(RevisionHash.of(Map.of("a", "2"))));
    }
}
