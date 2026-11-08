package com.securepay.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** When {@code enabled} is false (the default), no AWS client is created and no AWS call is ever made. */
@ConfigurationProperties("securepay.aws")
public record AwsProperties(boolean enabled, String region) {
}
