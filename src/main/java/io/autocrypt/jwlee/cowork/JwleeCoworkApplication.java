package io.autocrypt.jwlee.cowork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
class JwleeCoworkApplication {
	public static void main(String[] args) {
		SpringApplication.run(JwleeCoworkApplication.class, args);
	}
}
