package io.github.happyherait.pumui.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 구매요청 체크. 입력이 같으면 결과도 같은 순수 함수다. */
public final class CheckEngine {

    private static final Map<String, List<String>> REQUIRED_SPEC = Map.of(
            "CHEMICAL", List.of("순도", "CAS", "포장단위"),
            "EQUIPMENT", List.of("모델명", "정격전원", "설치장소"),
            "CONSUMABLE", List.of("규격"),
            "SERVICE", List.of("작업범위"));

    private CheckEngine() {}

    public static List<CheckResult> run(PurchaseFacts f) {
        List<CheckResult> out = new ArrayList<>();
        out.add(spec(f));
        out.add(budget(f));
        out.add(period(f));
        out.add(casMatch(f));
        out.add(hazard(f));
        out.add(strategic(f));
        out.add(soleSource(f));
        out.add(supplier(f));
        out.add(duplicate(f));
        out.add(price(f));
        return List.copyOf(out);
    }

    private static CheckResult spec(PurchaseFacts f) {
        List<String> required = REQUIRED_SPEC.get(f.category());
        if (required == null) {
            return new CheckResult("spec.required", "사양 완전성", CheckStatus.FAIL, "알 수 없는 분류입니다: " + f.category());
        }
        List<String> missing = required.stream()
                .filter(k -> blank(f.spec().get(k)))
                .toList();
        if (!missing.isEmpty()) {
            return new CheckResult("spec.required", "사양 완전성", CheckStatus.FAIL, "필수 사양 누락: " + String.join(", ", missing));
        }
        return new CheckResult("spec.required", "사양 완전성", CheckStatus.PASS, "필수 사양이 모두 있습니다.");
    }

    private static CheckResult budget(PurchaseFacts f) {
        long amount = f.amount();
        if (amount > f.budgetAvailable()) {
            return new CheckResult("budget.available", "과제 예산", CheckStatus.FAIL,
                    "요청 " + won(amount) + "이 가용 예산 " + won(f.budgetAvailable()) + "을 넘습니다.");
        }
        return new CheckResult("budget.available", "과제 예산", CheckStatus.PASS, "가용 예산 " + won(f.budgetAvailable()) + " 안입니다.");
    }

    private static CheckResult period(PurchaseFacts f) {
        if (blank(f.deliveryDate())) {
            return new CheckResult("budget.period", "과제 기간", CheckStatus.FAIL, "희망 납기가 없습니다.");
        }
        // ISO 날짜는 문자열 비교로 순서를 판단할 수 있다.
        if (f.deliveryDate().compareTo(f.projectEndDate()) > 0) {
            return new CheckResult("budget.period", "과제 기간", CheckStatus.FAIL,
                    "희망 납기가 과제 종료일 " + f.projectEndDate() + " 이후입니다.");
        }
        return new CheckResult("budget.period", "과제 기간", CheckStatus.PASS, "과제 기간 안입니다.");
    }

    private static CheckResult casMatch(PurchaseFacts f) {
        if (!blank(f.masterCasNo()) && !f.masterCasNo().equals(f.specCasNo())) {
            return new CheckResult("hazard.cas-match", "CAS 대조", CheckStatus.FAIL,
                    "사양의 CAS가 품목 마스터 " + f.masterCasNo() + "와 다릅니다.");
        }
        if ("CHEMICAL".equals(f.category()) && blank(f.itemCode())) {
            return new CheckResult("hazard.cas-match", "CAS 대조", CheckStatus.WARN, "품목 마스터에 없는 화학물질입니다.");
        }
        return new CheckResult("hazard.cas-match", "CAS 대조", CheckStatus.PASS, "품목 마스터와 일치하거나 대상이 아닙니다.");
    }

    private static CheckResult hazard(PurchaseFacts f) {
        if (f.hazard()) {
            return new CheckResult("hazard.regulated", "규제 물질", CheckStatus.REVIEW, "규제 물질 포함: " + String.join(", ", f.regulatedHits()));
        }
        return new CheckResult("hazard.regulated", "규제 물질", CheckStatus.PASS, "규제 물질 표에서 찾지 못했습니다.");
    }

    private static CheckResult strategic(PurchaseFacts f) {
        if (f.strategicItem()) {
            return new CheckResult("trade.strategic", "전략물자", CheckStatus.REVIEW, "전략물자 해당 품목입니다.");
        }
        return new CheckResult("trade.strategic", "전략물자", CheckStatus.PASS, "전략물자로 표시되지 않았습니다.");
    }

    private static CheckResult soleSource(PurchaseFacts f) {
        if (!f.soleSource()) {
            return new CheckResult("supplier.sole-source", "단독 구매", CheckStatus.PASS, "단독 구매가 아닙니다.");
        }
        if (blank(f.soleSourceReason())) {
            return new CheckResult("supplier.sole-source", "단독 구매", CheckStatus.FAIL, "단독 구매 사유가 없습니다.");
        }
        return new CheckResult("supplier.sole-source", "단독 구매", CheckStatus.REVIEW, "단독 구매 사유를 구매 담당이 확인합니다.");
    }

    private static CheckResult supplier(PurchaseFacts f) {
        if (f.newSupplier()) {
            return new CheckResult("supplier.new", "공급사 등록", CheckStatus.REVIEW, "처음 거래하는 공급사입니다.");
        }
        return new CheckResult("supplier.new", "공급사 등록", CheckStatus.PASS, "등록된 공급사입니다.");
    }

    private static CheckResult duplicate(PurchaseFacts f) {
        if (f.stockOnHand() >= f.quantity()) {
            return new CheckResult("duplicate.stock", "중복 구매", CheckStatus.WARN, "사내 재고로 충당할 수 있습니다: " + qty(f.stockOnHand()));
        }
        if (f.lastOrderDaysAgo() != null && f.lastOrderDaysAgo() <= 30) {
            return new CheckResult("duplicate.stock", "중복 구매", CheckStatus.WARN, f.lastOrderDaysAgo() + "일 전에 같은 품목을 발주했습니다.");
        }
        return new CheckResult("duplicate.stock", "중복 구매", CheckStatus.PASS, "최근 중복 구매가 없습니다.");
    }

    private static CheckResult price(PurchaseFacts f) {
        Long median = f.medianUnitPrice();
        if (median == null || median <= 0) {
            return new CheckResult("price.drift", "단가 변동", CheckStatus.PASS, "비교할 구매 이력이 없습니다.");
        }
        long percent = Math.round((f.unitPrice() - median) * 100.0 / median);
        String text = "최근 1년 중앙값 " + won(median) + " 대비 " + (percent >= 0 ? "+" : "") + percent + "%입니다.";
        if (f.priceSpike()) {
            return new CheckResult("price.drift", "단가 변동", CheckStatus.REVIEW, text);
        }
        if (f.unitPrice() * 10 >= median * 11) {
            return new CheckResult("price.drift", "단가 변동", CheckStatus.WARN, text);
        }
        return new CheckResult("price.drift", "단가 변동", CheckStatus.PASS, text);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String won(long v) {
        return String.format("%,d원", v);
    }

    private static String qty(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
