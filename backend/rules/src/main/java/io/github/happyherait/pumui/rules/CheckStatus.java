package io.github.happyherait.pumui.rules;

/** PASS 통과, WARN 참고, REVIEW 지정 리뷰 필요, FAIL 머지 차단. */
public enum CheckStatus {
    PASS,
    WARN,
    REVIEW,
    FAIL
}
