package io.github.happyherait.pumui.rules;

import java.util.List;
import java.util.Map;

/**
 * 체크에 필요한 사실 전체. 서버가 데이터베이스와 요청 원문에서 모아서 넘긴다.
 * 규제 정보는 AI 답변이 아니라 품목 마스터와 규제 물질 표에서 온다.
 */
public record PurchaseFacts(
        String category,
        String itemCode,
        double quantity,
        long unitPrice,
        Map<String, String> spec,
        String specCasNo,
        String masterCasNo,
        List<String> regulatedHits,
        boolean strategicItem,
        boolean newSupplier,
        boolean soleSource,
        String soleSourceReason,
        long budgetAvailable,
        String projectEndDate,
        String deliveryDate,
        double stockOnHand,
        Integer lastOrderDaysAgo,
        Long medianUnitPrice) {

    public long amount() {
        return Math.round(quantity * unitPrice);
    }

    public boolean hazard() {
        return !regulatedHits.isEmpty();
    }

    /** 최근 1년 중앙값보다 30% 이상 비싸면 true. */
    public boolean priceSpike() {
        return medianUnitPrice != null && medianUnitPrice > 0 && unitPrice * 10 >= medianUnitPrice * 13;
    }
}
