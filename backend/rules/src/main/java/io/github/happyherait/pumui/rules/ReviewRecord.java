package io.github.happyherait.pumui.rules;

/** decision은 APPROVED 또는 CHANGES_REQUESTED다. 시간순으로 넘긴다. */
public record ReviewRecord(String reviewerId, String role, String decision, String revisionHash) {}
