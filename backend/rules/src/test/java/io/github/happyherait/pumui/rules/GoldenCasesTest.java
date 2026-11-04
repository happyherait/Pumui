package io.github.happyherait.pumui.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 브라우저 데모의 TypeScript 엔진도 같은 파일로 검증한다. */
class GoldenCasesTest {

    @Test
    @SuppressWarnings("unchecked")
    void matchesGoldenCases() throws IOException {
        List<Object> cases = (List<Object>) MiniJson.parse(resource("golden-cases.json"));
        Owners owners = Owners.parse(resource("PROCUREMENTOWNERS"));
        assertFalse(cases.isEmpty());

        for (Object raw : cases) {
            Map<String, Object> c = (Map<String, Object>) raw;
            String name = (String) c.get("name");
            PurchaseFacts facts = facts((Map<String, Object>) c.get("facts"));
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");

            assertEquals(((Double) expect.get("amount")).longValue(), facts.amount(), name);
            List<CheckResult> results = CheckEngine.run(facts);
            Map<String, String> statuses = new LinkedHashMap<>();
            results.forEach(r -> statuses.put(r.id(), r.status().name()));
            ((Map<String, Object>) expect.get("statuses"))
                    .forEach((id, status) -> assertEquals(status, statuses.get(id), name + " / " + id));
            assertEquals(expect.get("reviewers"), owners.resolve(facts).stream().map(RequiredReview::role).toList(), name);

            boolean blocked = results.stream().anyMatch(r -> r.status() == CheckStatus.FAIL);
            assertEquals(expect.get("blocked"), blocked, name);
        }
    }

    @SuppressWarnings("unchecked")
    private static PurchaseFacts facts(Map<String, Object> f) {
        return new PurchaseFacts(
                (String) f.get("category"),
                (String) f.get("itemCode"),
                num(f, "quantity"),
                (long) num(f, "unitPrice"),
                Map.copyOf((Map<String, String>) (Map<String, ?>) f.get("spec")),
                (String) f.get("specCasNo"),
                (String) f.get("masterCasNo"),
                f.containsKey("regulatedHits")
                        ? ((List<Object>) f.get("regulatedHits")).stream().map(String::valueOf).toList()
                        : List.of(),
                Boolean.TRUE.equals(f.get("strategicItem")),
                Boolean.TRUE.equals(f.get("newSupplier")),
                Boolean.TRUE.equals(f.get("soleSource")),
                (String) f.get("soleSourceReason"),
                (long) num(f, "budgetAvailable"),
                (String) f.get("projectEndDate"),
                (String) f.get("deliveryDate"),
                num(f, "stockOnHand"),
                null,
                f.containsKey("medianUnitPrice") ? (long) num(f, "medianUnitPrice") : null);
    }

    private static double num(Map<String, Object> f, String key) {
        Object v = f.get(key);
        return v == null ? 0 : (Double) v;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = GoldenCasesTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
