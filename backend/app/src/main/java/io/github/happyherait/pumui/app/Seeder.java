package io.github.happyherait.pumui.app;

import io.github.happyherait.pumui.app.Models.RevisionContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 브라우저 데모와 같은 shared/seed.json으로 빈 데이터베이스를 채운다. */
@Component
public class Seeder implements ApplicationRunner {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final PurchaseRequestService service;
    private final boolean enabled;

    public Seeder(JdbcClient db, ObjectMapper json, PurchaseRequestService service, @Value("${pumui.seed:true}") boolean enabled) {
        this.db = db;
        this.json = json;
        this.service = service;
        this.enabled = enabled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled || db.sql("SELECT COUNT(*) FROM app_users").query(Long.class).single() > 0) {
            return;
        }
        Map<String, Object> seed = json.readValue(
                new ClassPathResource("seed.json").getContentAsString(StandardCharsets.UTF_8), Map.class);
        rows(seed, "users").forEach(r -> db.sql("INSERT INTO app_users (id, name, dept, role_code) VALUES (:id, :name, :dept, :role)")
                .paramSource(r).update());
        rows(seed, "projects").forEach(r -> db.sql("""
                INSERT INTO rnd_projects (code, name, owner_dept, budget_total, budget_used, end_date)
                VALUES (:code, :name, :ownerDept, :budgetTotal, :budgetUsed, TO_DATE(:endDate, 'YYYY-MM-DD'))
                """).paramSource(r).update());
        rows(seed, "suppliers").forEach(r -> db.sql("INSERT INTO suppliers (code, name, approved) VALUES (:code, :name, :flag)")
                .param("code", r.get("code")).param("name", r.get("name")).param("flag", flag(r.get("approved"))).update());
        rows(seed, "items").forEach(r -> db.sql("""
                INSERT INTO items (code, name, category, unit, cas_no, strategic)
                VALUES (:code, :name, :category, :unit, :cas, :flag)
                """)
                .param("code", r.get("code")).param("name", r.get("name")).param("category", r.get("category"))
                .param("unit", r.get("unit")).param("cas", r.get("casNo")).param("flag", flag(r.get("strategic"))).update());
        rows(seed, "regulated").forEach(r -> db.sql("INSERT INTO regulated_substances (cas_no, name, grade) VALUES (:casNo, :name, :grade)")
                .paramSource(r).update());
        rows(seed, "inventory").forEach(r -> db.sql("INSERT INTO inventory (item_code, location, quantity) VALUES (:itemCode, :location, :quantity)")
                .paramSource(r).update());
        rows(seed, "orders").forEach(r -> db.sql("""
                INSERT INTO purchase_orders (item_code, supplier, quantity, unit_price, amount, ordered_at)
                VALUES (:itemCode, :supplier, :quantity, :unitPrice, :quantity * :unitPrice, SYSTIMESTAMP - NUMTODSINTERVAL(:daysAgo, 'DAY'))
                """).paramSource(r).update());
        for (Map<String, Object> sample : rows(seed, "samples")) {
            if ("normal".equals(sample.get("key"))) {
                service.create("kim", json.convertValue(sample.get("draft"), RevisionContent.class));
            }
        }
    }

    private static int flag(Object value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> seed, String key) {
        return (List<Map<String, Object>>) seed.get(key);
    }
}
