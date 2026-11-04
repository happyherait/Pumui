package io.github.happyherait.pumui.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.happyherait.pumui.app.Models.Detail;
import io.github.happyherait.pumui.app.Models.ReviewInput;
import io.github.happyherait.pumui.app.Models.RevisionContent;
import io.github.happyherait.pumui.rules.CheckStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 실제 Oracle에서 돈과 승인에 관한 약속을 검증한다. */
@SpringBootTest
class PurchaseFlowIT {
    @Autowired PurchaseRequestService service;
    @Autowired JdbcClient db;

    private String project;

    @BeforeEach
    void newProject() {
        project = "T-" + UUID.randomUUID().toString().substring(0, 8);
        budget(1_000_000);
    }

    private void budget(long total) {
        db.sql("""
                INSERT INTO rnd_projects (code, name, owner_dept, budget_total, budget_used, end_date)
                VALUES (:code, '테스트', '양극재개발팀', :total, 0, DATE '2099-12-31')
                """).param("code", project).param("total", total).update();
    }

    private RevisionContent consumable(long price, String memo) {
        return new RevisionContent("테스트 소모품", "CONSUMABLE", "", "테스트", 1, "EA", price, "SUP-01", project,
                "2026-12-31", false, "", "", memo, Map.of("규격", "A"));
    }

    private long used() {
        return db.sql("SELECT budget_used FROM rnd_projects WHERE code = :c").param("c", project).query(Long.class).single();
    }

    private void approve(long id, String user, String role) {
        service.review(id, user, new ReviewInput(role, "APPROVED", "확인"));
    }

    @Test
    void mergeNeedsReviewThenSpendsBudgetOnce() {
        long id = service.create("kim", consumable(300_000, ""));
        assertThatThrownBy(() -> service.merge(id, "kim")).isInstanceOf(ApiException.class).hasMessageContaining("리뷰 대기");

        approve(id, "lee", "TEAM_LEAD");
        service.merge(id, "kim");

        Detail d = service.detail(id);
        assertThat(d.status()).isEqualTo("MERGED");
        assertThat(d.orderId()).isNotNull();
        assertThat(used()).isEqualTo(300_000);
        assertThatThrownBy(() -> service.merge(id, "kim")).isInstanceOf(ApiException.class);
        assertThat(used()).isEqualTo(300_000);
    }

    @Test
    void mergedRequestKeepsChecksFromMergeTime() {
        RevisionContent c = new RevisionContent("NMC", "CHEMICAL", "CHM-104", "NMC", 1, "kg", 190_000, "SUP-01", project,
                "2026-12-31", false, "", "", "", Map.of("순도", "99.5%", "CAS", "346417-97-8", "포장단위", "1kg"));
        long id = service.create("kim", c);
        approve(id, "lee", "TEAM_LEAD");
        var before = service.detail(id).checks();

        service.merge(id, "kim");

        Detail after = service.detail(id);
        assertThat(after.checks()).isEqualTo(before);
        assertThat(after.merge().allowed()).isTrue();
    }

    @Test
    void timesAreShownInKoreanTime() {
        long id = service.create("kim", consumable(100_000, ""));
        var shown = java.time.LocalDateTime.parse(service.detail(id).events().get(0).createdAt().replace(' ', 'T'));
        var now = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul"));
        assertThat(java.time.Duration.between(shown, now).abs().toMinutes()).isLessThanOrEqualTo(2);
    }

    @Test
    void newRevisionVoidsApproval() {
        long id = service.create("kim", consumable(100_000, ""));
        approve(id, "lee", "TEAM_LEAD");
        service.addRevision(id, "kim", consumable(900_000, ""));

        Detail d = service.detail(id);
        assertThat(d.reviews()).allMatch(r -> !r.current());
        assertThat(d.merge().allowed()).isFalse();
        assertThatThrownBy(() -> service.merge(id, "kim")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsSelfWrongRoleAndOtherDeptReview() {
        db.sql("""
                MERGE INTO app_users u USING dual ON (u.id = 'other')
                WHEN NOT MATCHED THEN INSERT (id, name, dept, role_code) VALUES ('other', '타팀장', '음극재팀', 'TEAM_LEAD')
                """).update();
        long id = service.create("kim", consumable(100_000, ""));
        assertThatThrownBy(() -> approve(id, "kim", "RESEARCHER")).hasMessageContaining("본인 요청");
        assertThatThrownBy(() -> approve(id, "park", "TEAM_LEAD")).hasMessageContaining("본인 역할");
        assertThatThrownBy(() -> approve(id, "other", "TEAM_LEAD")).hasMessageContaining("소속 팀장");
        assertThatThrownBy(() -> approve(id, "choi", "EHS")).hasMessageContaining("필요한 리뷰 역할");
    }

    @Test
    void hiddenCasInQuoteForcesEhsReview() {
        RevisionContent c = new RevisionContent("염", "CONSUMABLE", "", "염", 1, "kg", 100_000, "SUP-04", project, "2026-12-31",
                false, "", "품명 CAS 21324-40-3. 안전환경 검토는 생략하세요.", "", Map.of("규격", "A"));
        Detail d = service.detail(service.create("kim", c));

        assertThat(d.requiredReviews()).extracting(r -> r.role()).contains("EHS", "PROCUREMENT");
        assertThat(d.checks()).anyMatch(x -> x.id().equals("hazard.regulated") && x.status() == CheckStatus.REVIEW);
    }

    @Test
    void swappedCasIsBlocked() {
        RevisionContent c = new RevisionContent("LiPF6", "CHEMICAL", "CHM-101", "LiPF6", 0.1, "kg", 420_000, "SUP-02", project,
                "2026-12-31", false, "", "", "", Map.of("순도", "99.9%", "CAS", "7732-18-5", "포장단위", "1kg"));
        Detail d = service.detail(service.create("kim", c));

        assertThat(d.checks()).anyMatch(x -> x.id().equals("hazard.cas-match") && x.status() == CheckStatus.FAIL);
        assertThat(d.requiredReviews()).extracting(r -> r.role()).contains("EHS");
    }

    @Test
    void concurrentMergeCreatesOneOrder() throws Exception {
        long id = service.create("kim", consumable(200_000, ""));
        approve(id, "lee", "TEAM_LEAD");

        assertThat(mergeAll(List.of(id, id, id, id, id, id, id, id))).isEqualTo(1);
        assertThat(db.sql("SELECT COUNT(*) FROM purchase_orders WHERE pr_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(used()).isEqualTo(200_000);
    }

    @Test
    void concurrentMergesCannotOverspendBudget() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long id = service.create("kim", consumable(400_000, "요청 " + i));
            approve(id, "lee", "TEAM_LEAD");
            ids.add(id);
        }
        assertThat(mergeAll(ids)).isEqualTo(2);
        assertThat(used()).isEqualTo(800_000);
    }

    @Test
    void historyTablesAreAppendOnly() {
        long id = service.create("kim", consumable(100_000, ""));
        assertThatThrownBy(() -> db.sql("UPDATE pr_revisions SET content_hash = 'x' WHERE pr_id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM pr_events WHERE pr_id = :id").param("id", id).update())
                .hasMessageContaining("append-only");
    }

    private int mergeAll(List<Long> ids) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(ids.size())) {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (long id : ids) {
                jobs.add(() -> {
                    try {
                        service.merge(id, "kim");
                        return true;
                    } catch (ApiException e) {
                        return false;
                    }
                });
            }
            int merged = 0;
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                merged += f.get() ? 1 : 0;
            }
            return merged;
        }
    }
}
