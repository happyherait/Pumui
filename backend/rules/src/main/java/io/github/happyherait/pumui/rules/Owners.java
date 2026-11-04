package io.github.happyherait.pumui.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PROCUREMENTOWNERS 파일을 읽어 필요한 리뷰 역할을 계산한다. */
public final class Owners {

    private record Rule(List<String> conditions, List<String> roles, String source) {}

    private final List<Rule> rules;

    private Owners(List<Rule> rules) {
        this.rules = rules;
    }

    public static Owners parse(String text) {
        List<Rule> rules = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> conditions = new ArrayList<>();
            List<String> roles = new ArrayList<>();
            for (String token : line.split("\\s+")) {
                if (token.startsWith("@")) {
                    roles.add(token.substring(1));
                } else {
                    validate(token);
                    conditions.add(token);
                }
            }
            if (conditions.isEmpty() || roles.isEmpty()) {
                throw new IllegalArgumentException("잘못된 규칙: " + line);
            }
            rules.add(new Rule(conditions, roles, String.join(" ", conditions)));
        }
        return new Owners(rules);
    }

    public List<RequiredReview> resolve(PurchaseFacts f) {
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.conditions().stream().allMatch(c -> matches(c, f))) {
                for (String role : rule.roles()) {
                    reasons.computeIfAbsent(role, r -> new ArrayList<>()).add(rule.source());
                }
            }
        }
        return reasons.entrySet().stream()
                .map(e -> new RequiredReview(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    private static void validate(String c) {
        boolean known = c.equals("always") || c.equals("hazard") || c.equals("strategic") || c.equals("sole-source")
                || c.equals("new-supplier") || c.equals("price-spike") || c.startsWith("category=")
                || c.matches("amount>=\\d+");
        if (!known) {
            throw new IllegalArgumentException("알 수 없는 조건: " + c);
        }
    }

    private static boolean matches(String c, PurchaseFacts f) {
        if (c.startsWith("amount>=")) {
            return f.amount() >= Long.parseLong(c.substring("amount>=".length()));
        }
        if (c.startsWith("category=")) {
            return c.substring("category=".length()).equals(f.category());
        }
        return switch (c) {
            case "always" -> true;
            case "hazard" -> f.hazard();
            case "strategic" -> f.strategicItem();
            case "sole-source" -> f.soleSource();
            case "new-supplier" -> f.newSupplier();
            case "price-spike" -> f.priceSpike();
            default -> throw new IllegalArgumentException("알 수 없는 조건: " + c);
        };
    }
}
