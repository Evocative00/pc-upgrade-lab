package com.pcupgradelab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 실행 진입점. 하위 패키지의 Controller·Service·Repository 등을 찾아 애플리케이션을 구성한다. */
@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
