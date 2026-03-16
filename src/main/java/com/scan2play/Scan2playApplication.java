package com.scan2play;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class Scan2playApplication {

	public static void main(String[] args) {
		SpringApplication.run(Scan2playApplication.class, args);
	}
}
