-- P0：金额预算关闭时，应用额度策略允许币种为空。
ALTER TABLE light_ai.application_quota_policy
    ALTER COLUMN currency DROP NOT NULL;