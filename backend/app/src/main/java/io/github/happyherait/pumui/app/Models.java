package io.github.happyherait.pumui.app;

import io.github.happyherait.pumui.rules.CheckResult;
import io.github.happyherait.pumui.rules.MergeDecision;
import io.github.happyherait.pumui.rules.RequiredReview;
import java.util.List;
import java.util.Map;

public final class Models {
    private Models() {}

    public record RevisionContent(
            String title,
            String category,
            String itemCode,
            String itemName,
            double quantity,
            String unit,
            long unitPrice,
            String supplierCode,
            String projectCode,
            String deliveryDate,
            boolean soleSource,
            String soleSourceReason,
            String quoteText,
            String notes,
            Map<String, String> spec) {}

    public record User(String id, String name, String dept, String role) {}

    public record RevisionView(int revNo, String hash, String authorId, String createdAt, RevisionContent content) {}

    public record ReviewView(
            long id, String reviewerId, String reviewerName, String role, String decision, String comment,
            String revisionHash, boolean current, String createdAt) {}

    public record EventView(String actorId, String type, String detail, String createdAt) {}

    public record Summary(
            long id, String title, String status, String requesterName, long amount, int headRev, String createdAt) {}

    public record Detail(
            long id,
            String title,
            String status,
            User requester,
            int headRev,
            long amount,
            String headHash,
            List<RevisionView> revisions,
            List<CheckResult> checks,
            List<RequiredReview> requiredReviews,
            List<ReviewView> reviews,
            MergeDecision merge,
            List<EventView> events,
            Long orderId) {}

    public record ReviewInput(String role, String decision, String comment) {}

    public record DraftInput(String text, String quoteText) {}

    public record Suggestion(String field, String value, String reason) {}

    public record Suggestions(String summary, List<Suggestion> changes) {}
}
