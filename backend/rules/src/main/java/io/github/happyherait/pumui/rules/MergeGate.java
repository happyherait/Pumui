package io.github.happyherait.pumui.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 발주 전환 가능 여부를 판단한다. 현재 리비전 해시에 묶인 리뷰만 인정한다. */
public final class MergeGate {

    private MergeGate() {}

    public static MergeDecision evaluate(
            List<CheckResult> checks,
            List<RequiredReview> required,
            String requesterId,
            String currentHash,
            List<ReviewRecord> reviews) {
        List<String> blockers = new ArrayList<>();
        for (CheckResult check : checks) {
            if (check.status() == CheckStatus.FAIL) {
                blockers.add(check.label() + " 실패: " + check.message());
            }
        }

        // 역할별로 마지막 판단만 남긴다. 다시 승인하면 앞선 변경 요청을 대체한다.
        Map<String, ReviewRecord> latest = new LinkedHashMap<>();
        for (ReviewRecord review : reviews) {
            if (currentHash.equals(review.revisionHash()) && !requesterId.equals(review.reviewerId())) {
                latest.put(review.role(), review);
            }
        }

        Set<String> usedReviewers = new HashSet<>();
        for (RequiredReview need : required) {
            ReviewRecord review = latest.get(need.role());
            if (review == null) {
                blockers.add(need.role() + " 리뷰 대기");
            } else if ("CHANGES_REQUESTED".equals(review.decision())) {
                blockers.add(need.role() + " 변경 요청");
            } else if (!usedReviewers.add(review.reviewerId())) {
                blockers.add(need.role() + " 리뷰어 중복: 한 사람은 한 역할만 맡을 수 있습니다");
            } else {
            }
        }
        return new MergeDecision(blockers.isEmpty(), List.copyOf(blockers));
    }
}
