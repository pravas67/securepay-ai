package com.securepay.cloud;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (enforced in SecurityConfig). Read-only by construction. */
@RestController
@RequestMapping("/api/cloud")
public class CloudController {

    private final CloudReadService cloud;

    public CloudController(CloudReadService cloud) {
        this.cloud = cloud;
    }

    @GetMapping("/instances")
    CloudReadService.InstancesReport instances() {
        return cloud.instances();
    }

    @GetMapping("/costs")
    CloudReadService.CostReport costs() {
        return cloud.monthToDateCost();
    }
}
