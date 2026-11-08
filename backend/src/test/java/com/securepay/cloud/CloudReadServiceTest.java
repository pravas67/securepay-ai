package com.securepay.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Placement;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

class CloudReadServiceTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Test
    void disabledMeansNoAwsCallsAndAClearMessage() {
        CloudReadService service = new CloudReadService(provider(null), provider(null));

        assertThat(service.instances().enabled()).isFalse();
        assertThat(service.monthToDateCost().enabled()).isFalse();
    }

    @Test
    void summarisesInstances() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenReturn(DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(Instance.builder()
                        .instanceId("i-123").instanceType("t3.micro")
                        .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                        .placement(Placement.builder().availabilityZone("ap-south-1a").build())
                        .tags(Tag.builder().key("Name").value("securepay-app").build())
                        .launchTime(Instant.parse("2026-11-01T00:00:00Z"))
                        .build()).build())
                .build());
        CloudReadService service = new CloudReadService(provider(ec2), provider(mock(CostExplorerClient.class)));

        var report = service.instances();

        assertThat(report.enabled()).isTrue();
        assertThat(report.instances()).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo("i-123");
            assertThat(i.name()).isEqualTo("securepay-app");
            assertThat(i.state()).isEqualTo("running");
        });
    }

    @Test
    void awsErrorsBecomeAMessageNotACrash() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(ec2.describeInstances(any(DescribeInstancesRequest.class))).thenThrow(Ec2Exception.builder().message("denied").build());
        CloudReadService service = new CloudReadService(provider(ec2), provider(null));

        assertThat(service.instances().error()).contains("AWS request failed");
    }
}
