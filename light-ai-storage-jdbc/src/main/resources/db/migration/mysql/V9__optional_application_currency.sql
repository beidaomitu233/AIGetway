-- P0：金额预算关闭时，应用额度策略允许币种为空。
ALTER TABLE application_quota_policy
    MODIFY currency CHAR(3) NULL;