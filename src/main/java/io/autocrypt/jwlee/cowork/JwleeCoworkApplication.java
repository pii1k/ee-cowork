package io.autocrypt.jwlee.cowork;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@ComponentScan(
		basePackages = {"io.autocrypt.jwlee.cowork", "io.autocrypt.eeTeam.cowork"},
		excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.autocrypt\\.jwlee\\.cowork\\.agents\\.sample\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = "io\\.autocrypt\\.jwlee\\.cowork\\.agents\\.scaffold\\..*")
		}
)
class JwleeCoworkApplication {
	public static void main(String[] args) {
		SpringApplication.run(JwleeCoworkApplication.class, args);
	}
}
