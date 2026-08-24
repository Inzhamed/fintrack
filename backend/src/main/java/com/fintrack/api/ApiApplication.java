package com.fintrack.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@ConfigurationPropertiesScan   // Binds the @ConfigurationProperties records under .config
@EnableTransactionManagement
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
