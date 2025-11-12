package com.navercorp.cubridqa.builder.test;

import com.navercorp.cubridqa.builder.BuilderConfig;
import com.navercorp.cubridqa.builder.tester.ActualSampler;
import com.navercorp.cubridqa.builder.tester.demand.UtilizationSnapshot;

public class ActualSamplerTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== ActualSampler Test ===\n");

        BuilderConfig config = new BuilderConfig("/home/qahome/cubrid-testtools/CTP/builder_tester/conf/tester.conf");
        ActualSampler sampler = new ActualSampler(config);

        System.out.println("Sampling current utilization...");
        UtilizationSnapshot snapshot = sampler.sampleCurrentUtilization();

        System.out.println("Results:");
        System.out.println("  CPU (millicores): " + snapshot.getTotalCpuMillicores());
        System.out.println("  Memory (bytes): " + snapshot.getTotalMemBytes());
        System.out.println("  I/O Read (bytes/sec): " + snapshot.getTotalIoReadBytesPerSec());
        System.out.println("  I/O Write (bytes/sec): " + snapshot.getTotalIoWriteBytesPerSec());
        System.out.println("  IOPS: " + snapshot.getTotalIops());
        System.out.println("  Network (bytes/sec): " + snapshot.getTotalNetBytesPerSec());

        System.out.println("\n=== Test Complete ===");
    }
}
