package com.dsh.workbench.cloud;

import com.dsh.workbench.cloud.config.CloudProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CloudProperties.class)
public class WorkbenchCloudApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkbenchCloudApplication.class, args);
  }
}
