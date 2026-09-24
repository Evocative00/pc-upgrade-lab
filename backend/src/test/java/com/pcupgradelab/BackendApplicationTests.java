package com.pcupgradelab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import com.pcupgradelab.scan.ScanService;
import static org.assertj.core.api.Assertions.assertThat;

// local로 스캔 모듈을 활성화하고 test의 H2 설정으로 검사한다. 개인 MySQL에 연결하는 테스트가 아니다.
@SpringBootTest
@ActiveProfiles({"local", "test"})
class BackendApplicationTests {

    @Autowired ScanService scanService;

	@Test
	void contextLoads() {

        assertThat(scanService).isNotNull();
	}

}
