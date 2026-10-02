-- 기존 CPU 제원은 그대로 두고 Intel 공표 전력과 P/E 코어 제원을 추가한다.
-- 기본값/자동 환산/데이터 갱신 없이 모두 NULL로 시작한다.
-- TDP/PBP/MTP를 실측 소비전력으로 사용하지 않는다.
ALTER TABLE cpu_spec ADD COLUMN processor_base_power_w DECIMAL(8,2);
ALTER TABLE cpu_spec ADD COLUMN maximum_turbo_power_w DECIMAL(8,2);
ALTER TABLE cpu_spec ADD COLUMN performance_core_count SMALLINT;
ALTER TABLE cpu_spec ADD COLUMN efficient_core_count SMALLINT;
ALTER TABLE cpu_spec ADD COLUMN performance_core_base_clock_mhz INTEGER;
ALTER TABLE cpu_spec ADD COLUMN efficient_core_base_clock_mhz INTEGER;
ALTER TABLE cpu_spec ADD COLUMN performance_core_boost_clock_mhz INTEGER;
ALTER TABLE cpu_spec ADD COLUMN efficient_core_boost_clock_mhz INTEGER;

ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_base_power
    CHECK (processor_base_power_w IS NULL OR processor_base_power_w > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_turbo_power
    CHECK (maximum_turbo_power_w IS NULL OR maximum_turbo_power_w > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_power_order CHECK (
    processor_base_power_w IS NULL OR maximum_turbo_power_w IS NULL
    OR maximum_turbo_power_w >= processor_base_power_w
);

-- NULL=미확인, 0=해당 코어 유형 없음 확인. 총 코어가 알려졌으면 개수도 일치해야 한다.
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_core_count
    CHECK (performance_core_count IS NULL OR performance_core_count >= 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_core_count
    CHECK (efficient_core_count IS NULL OR efficient_core_count >= 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_core_total CHECK (
    core_count IS NULL OR performance_core_count IS NULL OR performance_core_count <= core_count
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_core_total CHECK (
    core_count IS NULL OR efficient_core_count IS NULL OR efficient_core_count <= core_count
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_core_split CHECK (
    performance_core_count IS NULL OR efficient_core_count IS NULL
    OR (performance_core_count + efficient_core_count > 0
        AND (core_count IS NULL OR core_count = performance_core_count + efficient_core_count))
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_hybrid_base CHECK (
    base_clock_mhz IS NULL OR performance_core_count IS NULL OR efficient_core_count IS NULL
    OR performance_core_count = 0 OR efficient_core_count = 0
);

ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_base_clock
    CHECK (performance_core_base_clock_mhz IS NULL OR performance_core_base_clock_mhz > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_base_clock
    CHECK (efficient_core_base_clock_mhz IS NULL OR efficient_core_base_clock_mhz > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_boost_clock
    CHECK (performance_core_boost_clock_mhz IS NULL OR performance_core_boost_clock_mhz > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_boost_clock
    CHECK (efficient_core_boost_clock_mhz IS NULL OR efficient_core_boost_clock_mhz > 0);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_clock_presence CHECK (
    performance_core_count IS NULL OR performance_core_count <> 0
    OR (performance_core_base_clock_mhz IS NULL AND performance_core_boost_clock_mhz IS NULL)
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_clock_presence CHECK (
    efficient_core_count IS NULL OR efficient_core_count <> 0
    OR (efficient_core_base_clock_mhz IS NULL AND efficient_core_boost_clock_mhz IS NULL)
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_clock_order CHECK (
    performance_core_base_clock_mhz IS NULL OR performance_core_boost_clock_mhz IS NULL
    OR performance_core_boost_clock_mhz >= performance_core_base_clock_mhz
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_clock_order CHECK (
    efficient_core_base_clock_mhz IS NULL OR efficient_core_boost_clock_mhz IS NULL
    OR efficient_core_boost_clock_mhz >= efficient_core_base_clock_mhz
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_p_boost_limit CHECK (
    boost_clock_mhz IS NULL OR performance_core_boost_clock_mhz IS NULL
    OR performance_core_boost_clock_mhz <= boost_clock_mhz
);
ALTER TABLE cpu_spec ADD CONSTRAINT ck_cpu_e_boost_limit CHECK (
    boost_clock_mhz IS NULL OR efficient_core_boost_clock_mhz IS NULL
    OR efficient_core_boost_clock_mhz <= boost_clock_mhz
);
