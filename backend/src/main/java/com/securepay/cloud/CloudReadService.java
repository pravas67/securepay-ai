package com.securepay.cloud;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.DateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageRequest;
import software.amazon.awssdk.services.costexplorer.model.Granularity;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinition;
import software.amazon.awssdk.services.costexplorer.model.GroupDefinitionType;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Read-only views of the AWS account. There is intentionally no method here that changes anything:
 * the IAM policy doesn't allow it, and this class has no code path for it either.
 */
@Service
public class CloudReadService {

    static final int MAX_INSTANCES = 50;
    static final int TOP_SERVICES = 10;

    private final ObjectProvider<Ec2Client> ec2;
    private final ObjectProvider<CostExplorerClient> costExplorer;

    public CloudReadService(ObjectProvider<Ec2Client> ec2, ObjectProvider<CostExplorerClient> costExplorer) {
        this.ec2 = ec2;
        this.costExplorer = costExplorer;
    }

    public InstancesReport instances() {
        Ec2Client client = ec2.getIfAvailable();
        if (client == null) {
            return new InstancesReport(false, "AWS integration is disabled (set AWS_ENABLED=true).", List.of());
        }
        try {
            // One page of up to 50 instances is plenty for a dashboard; no unbounded pagination.
            List<InstanceSummary> instances = client.describeInstances(DescribeInstancesRequest.builder().maxResults(MAX_INSTANCES).build())
                    .reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .limit(MAX_INSTANCES)
                    .map(CloudReadService::summarise)
                    .toList();
            return new InstancesReport(true, null, instances);
        } catch (SdkException e) {
            return new InstancesReport(true, "AWS request failed: " + e.getClass().getSimpleName(), List.of());
        }
    }

    public CostReport monthToDateCost() {
        CostExplorerClient client = costExplorer.getIfAvailable();
        if (client == null) {
            return new CostReport(false, "AWS integration is disabled (set AWS_ENABLED=true).", null, null, null, List.of());
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.withDayOfMonth(1);
        // Cost Explorer's end date is exclusive and must be after the start.
        LocalDate end = today.plusDays(1);
        try {
            var response = client.getCostAndUsage(GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build())
                    .build());
            List<ServiceCost> byService = response.resultsByTime().stream()
                    .flatMap(r -> r.groups().stream())
                    .map(g -> new ServiceCost(g.keys().getFirst(),
                            new BigDecimal(g.metrics().get("UnblendedCost").amount()).setScale(2, RoundingMode.HALF_UP),
                            g.metrics().get("UnblendedCost").unit()))
                    .sorted(Comparator.comparing(ServiceCost::amount).reversed())
                    .toList();
            BigDecimal total = byService.stream().map(ServiceCost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String unit = byService.isEmpty() ? "USD" : byService.getFirst().unit();
            return new CostReport(true, null, start + " to " + today, total, unit, byService.stream().limit(TOP_SERVICES).toList());
        } catch (SdkException e) {
            return new CostReport(true, "AWS request failed: " + e.getClass().getSimpleName(), null, null, null, List.of());
        }
    }

    private static InstanceSummary summarise(Instance i) {
        String name = i.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(null);
        return new InstanceSummary(i.instanceId(), name, i.instanceTypeAsString(), i.state().nameAsString(),
                i.placement() == null ? null : i.placement().availabilityZone(), i.launchTime());
    }

    public record InstancesReport(boolean enabled, String error, List<InstanceSummary> instances) {
    }

    public record InstanceSummary(String id, String name, String type, String state, String availabilityZone, Instant launchedAt) {
    }

    public record CostReport(boolean enabled, String error, String period, BigDecimal total, String unit, List<ServiceCost> topServices) {
    }

    public record ServiceCost(String service, BigDecimal amount, String unit) {
    }
}
