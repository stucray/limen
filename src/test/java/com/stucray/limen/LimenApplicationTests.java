package com.stucray.limen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("Spring application context")
class LimenApplicationTests {

	@Test
	@DisplayName("Boots cleanly against Postgres + the full bean graph")
	void contextLoads() {
	}

}
