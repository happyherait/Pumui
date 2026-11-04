package io.github.happyherait.pumui.app;

import io.github.happyherait.pumui.app.Models.RevisionContent;
import io.github.happyherait.pumui.rules.PurchaseFacts;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 요청 내용과 데이터베이스에서 체크용 사실을 모은다.
 * 규제 여부는 작성자나 AI가 고른 분류를 믿지 않고 원문 전체에서 CAS 번호를 찾아 표와 대조한다.
 */
@Component
public class FactsAssembler {
    private static final Pattern CAS = Pattern.compile("(?<![\\d-])\\d{2,7}-\\d{2}-\\d(?![\\d-])");

    private final JdbcClient db;

    public FactsAssembler(JdbcClient db) {
        this.db = db;
    }

    public PurchaseFacts assemble(RevisionContent c) {
        Map<String, Object> project = db.sql("""
                SELECT budget_total - budget_used AS available, TO_CHAR(end_date, 'YYYY-MM-DD') AS end_date
                FROM rnd_projects WHERE code = :code
                """)
                .param("code", c.projectCode())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_PROJECT", "과제 코드를 찾을 수 없습니다."));

        Map<String, Object> item = blank(c.itemCode()) ? Map.of() : db.sql("SELECT cas_no, strategic FROM items WHERE code = :code")
                .param("code", c.itemCode())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_ITEM", "품목 코드를 찾을 수 없습니다."));
        String masterCas = (String) item.get("CAS_NO");
        boolean strategic = item.get("STRATEGIC") != null && ((Number) item.get("STRATEGIC")).intValue() == 1;

        List<Integer> approved = db.sql("SELECT approved FROM suppliers WHERE code = :code")
                .param("code", c.supplierCode())
                .query(Integer.class)
                .list();
        boolean newSupplier = approved.isEmpty() || approved.get(0) == 0;

        double stock = 0;
        Long median = null;
        Integer lastDays = null;
        if (!blank(c.itemCode())) {
            stock = db.sql("SELECT NVL(SUM(quantity), 0) FROM inventory WHERE item_code = :code")
                    .param("code", c.itemCode())
                    .query(BigDecimal.class)
                    .single()
                    .doubleValue();
            Map<String, Object> history = db.sql("""
                    SELECT ROUND(MEDIAN(unit_price)) AS median_price,
                           TRUNC(SYSDATE) - TRUNC(CAST(MAX(ordered_at) AS DATE)) AS days_ago
                    FROM purchase_orders
                    WHERE item_code = :code AND ordered_at >= ADD_MONTHS(SYSTIMESTAMP, -12)
                    """)
                    .param("code", c.itemCode())
                    .query()
                    .singleRow();
            if (history.get("MEDIAN_PRICE") != null) {
                median = ((Number) history.get("MEDIAN_PRICE")).longValue();
                lastDays = ((Number) history.get("DAYS_AGO")).intValue();
            }
        }

        return new PurchaseFacts(
                c.category(),
                c.itemCode(),
                c.quantity(),
                c.unitPrice(),
                c.spec(),
                c.spec().get("CAS"),
                masterCas,
                regulatedHits(c, masterCas),
                strategic,
                newSupplier,
                c.soleSource(),
                c.soleSourceReason(),
                ((Number) project.get("AVAILABLE")).longValue(),
                (String) project.get("END_DATE"),
                c.deliveryDate(),
                stock,
                lastDays,
                median);
    }

    private List<String> regulatedHits(RevisionContent c, String masterCas) {
        Set<String> found = new LinkedHashSet<>(casNumbers(c));
        if (!blank(masterCas)) {
            found.add(masterCas);
        }
        if (found.isEmpty()) {
            return List.of();
        }
        return db.sql("SELECT cas_no || ' ' || name FROM regulated_substances WHERE cas_no IN (:cas) ORDER BY cas_no")
                .param("cas", new ArrayList<>(found))
                .query(String.class)
                .list();
    }

    static Set<String> casNumbers(RevisionContent c) {
        String text = String.join("\n", nz(c.title()), nz(c.itemName()), nz(c.notes()), nz(c.quoteText()),
                String.join("\n", c.spec().values()));
        Set<String> out = new LinkedHashSet<>();
        Matcher m = CAS.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
