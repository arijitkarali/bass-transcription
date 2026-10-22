package com.example.basstranscription;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_INFRA_TESTS", matches="true")
@SpringBootTest
class BassTranscriptionApplicationTests {

	@Test
	void contextLoads() {
	}

}
