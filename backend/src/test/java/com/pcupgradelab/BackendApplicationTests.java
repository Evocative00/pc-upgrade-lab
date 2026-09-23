package com.pcupgradelab;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import com.pcupgradelab.scan.ScanService;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"local", "test"})
class BackendApplicationTests {

    @Autowired ScanService scanService;

	@Test
	void contextLoads() {

        assertThat(scanService).isNotNull();
	}

}
