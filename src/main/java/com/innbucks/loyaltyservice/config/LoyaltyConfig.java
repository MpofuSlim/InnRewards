package com.innbucks.loyaltyservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LoyaltyProperties.class, VoucherGuardProperties.class, SupportProperties.class})
public class LoyaltyConfig {
}
