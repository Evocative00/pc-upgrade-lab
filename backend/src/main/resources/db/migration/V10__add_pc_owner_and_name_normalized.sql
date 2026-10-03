-- 로그인 회원별 PC 소유권과 회원 안의 이름 중복 방지를 추가한다. (이슈 #18)
-- owner_key는 더 이상 사용하지 않는다. 기존 행 보존을 위해 컬럼은 남기고 NULL만 허용한다.
ALTER TABLE pc_configuration MODIFY owner_key VARCHAR(128) NULL;

-- 소유 회원. 기존 local-dev 데이터는 첫 로그인 회원에게 귀속하지 않도록 NULL로 둔다.
-- users 테이블 FK는 회원 테이블을 만드는 마이그레이션에서 추가한다.
ALTER TABLE pc_configuration ADD COLUMN user_id BIGINT NULL;

-- 이름 비교용 값: 앞뒤 공백 제거 + 소문자. 서버(PcConfiguration.normalizeName)와 같은 기준이다.
ALTER TABLE pc_configuration ADD COLUMN name_normalized VARCHAR(100) NULL;
UPDATE pc_configuration SET name_normalized = LOWER(TRIM(name));
ALTER TABLE pc_configuration MODIFY name_normalized VARCHAR(100) NOT NULL;

-- 같은 회원 안에서만 이름 중복을 막는다. user_id가 NULL인 기존 행끼리는 제약 대상이 아니다.
ALTER TABLE pc_configuration ADD CONSTRAINT uk_pc_configuration_user_name UNIQUE (user_id, name_normalized);
CREATE INDEX idx_pc_configuration_user ON pc_configuration(user_id);
