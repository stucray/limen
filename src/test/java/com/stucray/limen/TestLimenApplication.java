package com.stucray.limen;

import org.springframework.boot.SpringApplication;

public class TestLimenApplication {

	public static void main(String[] args) {
		SpringApplication.from(LimenApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}

}
