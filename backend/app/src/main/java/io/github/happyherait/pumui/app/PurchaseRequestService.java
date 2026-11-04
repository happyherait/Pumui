package io.github.happyherait.pumui.app;

import io.github.happyherait.pumui.app.Models.Detail;
import io.github.happyherait.pumui.app.Models.EventView;
import io.github.happyherait.pumui.app.Models.ReviewInput;
import io.github.happyherait.pumui.app.Models.ReviewView;
import io.github.happyherait.pumui.app.Models.RevisionContent;
import io.github.happyherait.pumui.app.Models.RevisionView;
import io.github.happyherait.pumui.app.Models.Summary;
import io.github.happyherait.pumui.app.Models.User;
import io.github.happyherait.pumui.rules.CheckEngine;
import io.github.happyherait.pumui.rules.CheckResult;
import io.github.happyherait.pumui.rules.MergeDecision;
import io.github.happyherait.pumui.rules.MergeGate;
import io.github.happyherait.pumui.rules.Owners;
import io.github.happyherait.pumui.rules.PurchaseFacts;
import io.github.happyherait.pumui.rules.RequiredReview;
import io.github.happyherait.pumui.rules.ReviewRecord;
import io.github.happyherait.pumui.rules.RevisionHash;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class PurchaseRequestService {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final FactsAssembler facts;
    private final Owners owners;

    public PurchaseRequestService(JdbcClient db, ObjectMapper json, FactsAssembler facts) throws IOException {
        this.db = db;
        this.json = json;
        this.facts = facts;
        this.owners = Owners.parse(new ClassPathResource("PROCUREMENTOWNERS").getContentAsString(StandardCharsets.UTF_8));
    }

    public User user(String id) {
        return db.sql("SELECT id, name, dept, role_code FROM app_users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNKNOWN_USER", "사용자를 확인할 수 없습니다."));
    }

    public List<Summary> list() {
        return db.sql("""
                SELECT r.id, r.title, r.status, u.name, r.head_rev, TO_CHAR(FROM_TZ(r.created_at, '+00:00') AT TIME ZONE '+09:00', 'YYYY-MM-DD HH24:MI'),
                       JSON_VALUE(v.content, '$.quantity' RETURNING NUMBER) * JSON_VALUE(v.content, '$.unitPrice' RETURNING NUMBER)
                FROM purchase_requests r
                JOIN app_users u ON u.id = r.requester_id
                JOIN pr_revisions v ON v.pr_id = r.id AND v.rev_no = r.head_rev
                ORDER BY r.id DESC
                """)
                .query((rs, n) -> new Summary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Math.round(rs.getDouble(7)), rs.getInt(5), rs.getString(6)))
                .list();
    }

    @Transactional
    public long create(String userId, RevisionContent content) {
        User requester = user(userId);
        RevisionContent c = validate(content);
        facts.assemble(c);
        long id = db.sql("SELECT pr_seq.NEXTVAL FROM dual").query(Long.class).single();
        db.sql("INSERT INTO purchase_requests (id, title, requester_id) VALUES (:id, :title, :user)")
                .param("id", id)
                .param("title", c.title())
                .param("user", requester.id())
                .update();
        insertRevision(id, 1, c, requester.id());
        event(id, requester.id(), "OPENED", "구매요청을 열었습니다.");
        return id;
    }

    @Transactional
    public void addRevision(long id, String userId, RevisionContent content) {
        Row row = lock(id);
        requireOpen(row);
        if (!row.requesterId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_REQUESTER", "요청자만 사양을 고칠 수 있습니다.");
        }
        RevisionContent c = validate(content);
        facts.assemble(c);
        int next = row.headRev() + 1;
        insertRevision(id, next, c, userId);
        db.sql("UPDATE purchase_requests SET head_rev = :rev, title = :title WHERE id = :id")
                .param("rev", next)
                .param("title", c.title())
                .param("id", id)
                .update();
        event(id, userId, "REVISED", "리비전 " + next + "을 올렸습니다. 이전 승인은 무효가 됩니다.");
    }

    @Transactional
    public void review(long id, String userId, ReviewInput input) {
        Row row = lock(id);
        requireOpen(row);
        User reviewer = user(userId);
        if (!List.of("APPROVED", "CHANGES_REQUESTED").contains(input.decision())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_DECISION", "알 수 없는 리뷰 결정입니다.");
        }
        if (reviewer.id().equals(row.requesterId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_REVIEW", "본인 요청은 리뷰할 수 없습니다.");
        }
        if (!reviewer.role().equals(input.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_MISMATCH", "본인 역할로만 리뷰할 수 있습니다.");
        }
        if ("TEAM_LEAD".equals(input.role()) && !user(row.requesterId()).dept().equals(reviewer.dept())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "OTHER_DEPT", "요청자 소속 팀장만 리뷰할 수 있습니다.");
        }
        RevisionView head = revision(id, row.headRev());
        PurchaseFacts f = facts.assemble(head.content());
        if (owners.resolve(f).stream().noneMatch(r -> r.role().equals(input.role()))) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REQUIRED", "이 요청에 필요한 리뷰 역할이 아닙니다.");
        }
        db.sql("""
                INSERT INTO pr_reviews (pr_id, reviewer_id, role_code, decision, revision_hash, comment_text)
                VALUES (:id, :user, :role, :decision, :hash, :comment)
                """)
                .param("id", id)
                .param("user", userId)
                .param("role", input.role())
                .param("decision", input.decision())
                .param("hash", head.hash())
                .param("comment", input.comment())
                .update();
        event(id, userId, input.decision(), input.role() + " 리뷰를 남겼습니다.");
    }

    /** 머지는 항상 잠금 안에서 최신 사실로 다시 판단한다. */
    @Transactional
    public long merge(long id, String userId) {
        Row row = lock(id);
        requireOpen(row);
        User actor = user(userId);
        if (!actor.id().equals(row.requesterId()) && !"PROCUREMENT".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_MERGE", "요청자 또는 구매 담당만 발주로 전환할 수 있습니다.");
        }
        RevisionView head = revision(id, row.headRev());
        RevisionContent c = head.content();
        PurchaseFacts f = facts.assemble(c);
        List<CheckResult> checks = CheckEngine.run(f);
        List<RequiredReview> required = owners.resolve(f);
        MergeDecision decision = MergeGate.evaluate(checks, required, row.requesterId(), head.hash(), reviewRecords(id));
        if (!decision.allowed()) {
            event(id, userId, "MERGE_REJECTED", String.join("; ", decision.blockers()));
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_BLOCKED", String.join("; ", decision.blockers()));
        }
        int updated = db.sql("""
                UPDATE rnd_projects SET budget_used = budget_used + :amount
                WHERE code = :code AND budget_total - budget_used >= :amount
                """)
                .param("amount", f.amount())
                .param("code", c.projectCode())
                .update();
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "BUDGET_CHANGED", "그사이 가용 예산이 부족해졌습니다.");
        }
        db.sql("""
                INSERT INTO purchase_orders (pr_id, item_code, supplier, quantity, unit_price, amount)
                VALUES (:id, :item, :supplier, :quantity, :price, :amount)
                """)
                .param("id", id)
                .param("item", c.itemCode() == null || c.itemCode().isBlank() ? null : c.itemCode())
                .param("supplier", c.supplierCode())
                .param("quantity", c.quantity())
                .param("price", c.unitPrice())
                .param("amount", f.amount())
                .update();
        db.sql("INSERT INTO pr_merge_snapshots (pr_id, checks, required) VALUES (:id, :checks, :required)")
                .param("id", id)
                .param("checks", json.writeValueAsString(checks))
                .param("required", json.writeValueAsString(required))
                .update();
        db.sql("UPDATE purchase_requests SET status = 'MERGED' WHERE id = :id").param("id", id).update();
        event(id, userId, "MERGED", "발주로 전환했습니다.");
        return db.sql("SELECT id FROM purchase_orders WHERE pr_id = :id").param("id", id).query(Long.class).single();
    }

    @Transactional
    public void close(long id, String userId) {
        Row row = lock(id);
        requireOpen(row);
        if (!row.requesterId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_REQUESTER", "요청자만 닫을 수 있습니다.");
        }
        db.sql("UPDATE purchase_requests SET status = 'CLOSED' WHERE id = :id").param("id", id).update();
        event(id, userId, "CLOSED", "구매요청을 닫았습니다.");
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        Row row = find(id, false);
        List<RevisionView> revisions = db.sql("""
                SELECT rev_no, content_hash, author_id, TO_CHAR(FROM_TZ(created_at, '+00:00') AT TIME ZONE '+09:00', 'YYYY-MM-DD HH24:MI'), content
                FROM pr_revisions WHERE pr_id = :id ORDER BY rev_no
                """)
                .param("id", id)
                .query((rs, n) -> new RevisionView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        json.readValue(rs.getString(5), RevisionContent.class)))
                .list();
        RevisionView head = revisions.get(revisions.size() - 1);
        PurchaseFacts f = facts.assemble(head.content());
        Snapshot snapshot = snapshot(id);
        List<CheckResult> checks = snapshot != null ? snapshot.checks() : CheckEngine.run(f);
        List<RequiredReview> required = snapshot != null ? snapshot.required() : owners.resolve(f);
        List<ReviewView> reviews = db.sql("""
                SELECT p.id, p.reviewer_id, u.name, p.role_code, p.decision, p.comment_text, p.revision_hash,
                       TO_CHAR(FROM_TZ(p.created_at, '+00:00') AT TIME ZONE '+09:00', 'YYYY-MM-DD HH24:MI')
                FROM pr_reviews p JOIN app_users u ON u.id = p.reviewer_id
                WHERE p.pr_id = :id ORDER BY p.id
                """)
                .param("id", id)
                .query((rs, n) -> new ReviewView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), head.hash().equals(rs.getString(7)), rs.getString(8)))
                .list();
        List<EventView> events = db.sql("""
                SELECT actor_id, event_type, detail, TO_CHAR(FROM_TZ(created_at, '+00:00') AT TIME ZONE '+09:00', 'YYYY-MM-DD HH24:MI')
                FROM pr_events WHERE pr_id = :id ORDER BY id
                """)
                .param("id", id)
                .query((rs, n) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        Long orderId = db.sql("SELECT id FROM purchase_orders WHERE pr_id = :id")
                .param("id", id)
                .query(Long.class)
                .optional()
                .orElse(null);
        return new Detail(id, head.content().title(), row.status(), user(row.requesterId()), row.headRev(), f.amount(), head.hash(),
                revisions, checks, required, reviews,
                snapshot != null
                        ? new MergeDecision(true, List.of())
                        : MergeGate.evaluate(checks, required, row.requesterId(), head.hash(), reviewRecords(id)),
                events, orderId);
    }

    private record Snapshot(List<CheckResult> checks, List<RequiredReview> required) {}

    /** 발주 후에는 예산과 이력이 바뀌므로 발주 시점의 판단을 그대로 보여 준다. */
    private Snapshot snapshot(long id) {
        return db.sql("SELECT checks, required FROM pr_merge_snapshots WHERE pr_id = :id")
                .param("id", id)
                .query((rs, n) -> new Snapshot(
                        json.readValue(rs.getString(1), new TypeReference<List<CheckResult>>() {}),
                        json.readValue(rs.getString(2), new TypeReference<List<RequiredReview>>() {})))
                .optional()
                .orElse(null);
    }

    private List<ReviewRecord> reviewRecords(long id) {
        return db.sql("SELECT reviewer_id, role_code, decision, revision_hash FROM pr_reviews WHERE pr_id = :id ORDER BY id")
                .param("id", id)
                .query((rs, n) -> new ReviewRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
    }

    private RevisionView revision(long id, int rev) {
        return db.sql("""
                SELECT rev_no, content_hash, author_id, TO_CHAR(FROM_TZ(created_at, '+00:00') AT TIME ZONE '+09:00', 'YYYY-MM-DD HH24:MI'), content
                FROM pr_revisions WHERE pr_id = :id AND rev_no = :rev
                """)
                .param("id", id)
                .param("rev", rev)
                .query((rs, n) -> new RevisionView(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        json.readValue(rs.getString(5), RevisionContent.class)))
                .single();
    }

    private void insertRevision(long id, int rev, RevisionContent c, String author) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.convertValue(c, Map.class);
        db.sql("""
                INSERT INTO pr_revisions (pr_id, rev_no, content, content_hash, author_id)
                VALUES (:id, :rev, :content, :hash, :author)
                """)
                .param("id", id)
                .param("rev", rev)
                .param("content", json.writeValueAsString(c))
                .param("hash", RevisionHash.of(map))
                .param("author", author)
                .update();
    }

    private void event(long id, String actor, String type, String detail) {
        db.sql("INSERT INTO pr_events (pr_id, actor_id, event_type, detail) VALUES (:id, :actor, :type, :detail)")
                .param("id", id)
                .param("actor", actor)
                .param("type", type)
                .param("detail", detail.length() > 900 ? detail.substring(0, 900) : detail)
                .update();
    }

    private record Row(String requesterId, String status, int headRev) {}

    private Row lock(long id) {
        return find(id, true);
    }

    private Row find(long id, boolean forUpdate) {
        return db.sql("SELECT requester_id, status, head_rev FROM purchase_requests WHERE id = :id" + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "구매요청을 찾을 수 없습니다."));
    }

    private static void requireOpen(Row row) {
        if (!"OPEN".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_OPEN", "이미 끝난 구매요청입니다.");
        }
    }

    private static RevisionContent validate(RevisionContent c) {
        if (c == null || c.title() == null || c.title().isBlank() || c.title().length() > 200) {
            throw invalid("제목을 200자 안으로 입력하세요.");
        }
        if (c.quantity() <= 0 || c.unitPrice() < 0) {
            throw invalid("수량과 단가를 확인하세요.");
        }
        if (c.quoteText() != null && c.quoteText().length() > 8000) {
            throw invalid("견적서 본문은 8,000자까지 넣을 수 있습니다.");
        }
        if (c.spec() == null) {
            throw invalid("사양이 없습니다.");
        }
        if (c.deliveryDate() != null && !c.deliveryDate().isBlank() && !c.deliveryDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw invalid("납기는 YYYY-MM-DD 형식으로 입력하세요.");
        }
        return c;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID", message);
    }
}
