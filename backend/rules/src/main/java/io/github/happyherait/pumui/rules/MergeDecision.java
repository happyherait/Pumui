package io.github.happyherait.pumui.rules;

import java.util.List;

public record MergeDecision(boolean allowed, List<String> blockers) {}
