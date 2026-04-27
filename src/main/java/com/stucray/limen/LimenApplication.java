package com.stucray.limen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LimenApplication {

	public static void main(String[] args) {
		SpringApplication.run(LimenApplication.class, args);
	}

}
