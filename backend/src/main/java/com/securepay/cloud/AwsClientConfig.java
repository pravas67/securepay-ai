package com.securepay.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * Credentials come from the default AWS chain (env vars, ~/.aws profile locally, the EC2 instance role in AWS).
 * They never pass through this application's config, the database, or the AI. Grant only the read-only policy in
 * infra/aws/iam-readonly-policy.json.
 */
@Configuration
@ConditionalOnProperty(name = "securepay.aws.enabled", havingValue = "true")
public class AwsClientConfig {

    @Bean(destroyMethod = "close")
    Ec2Client ec2Client(AwsProperties properties) {
        return Ec2Client.builder().region(Region.of(properties.region())).build();
    }

    /** Cost Explorer is a global service served from us-east-1. */
    @Bean(destroyMethod = "close")
    CostExplorerClient costExplorerClient() {
        return CostExplorerClient.builder().region(Region.US_EAST_1).build();
    }
}
