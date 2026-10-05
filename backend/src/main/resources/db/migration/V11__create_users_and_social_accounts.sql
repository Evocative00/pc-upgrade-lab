-- OAuth 계정은 이메일이 아닌 (provider, provider_user_id)로 식별한다.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE social_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_social_accounts_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_social_accounts_user FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE INDEX idx_social_accounts_user ON social_accounts(user_id);

-- 기존 local-dev 행의 user_id=NULL은 그대로 둔다. 숫자 ID가 이미 들어 있는
-- 개발 데이터가 있다면 FK 적용 전에 소유자를 확인하고 별도로 정리해야 한다.
ALTER TABLE pc_configuration
    ADD CONSTRAINT fk_pc_configuration_user FOREIGN KEY (user_id) REFERENCES users(id);
