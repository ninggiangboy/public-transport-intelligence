package dev.pti.spike;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;

@TestConfiguration(proxyBeanMethods = false)
class InfraContainers {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.1");
    }

    @Bean
    GenericContainer<?> seaweedfs() {
        return new GenericContainer<>("chrislusf/seaweedfs:4.47")
                .withCopyToContainer(
                        org.testcontainers.images.builder.Transferable.of(
                                "{\"identities\":[{\"name\":\"spike\",\"credentials\":[{\"accessKey\":\"spike\",\"secretKey\":\"spike\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\"]}]}"),
                        "/etc/seaweedfs/s3.json")
                .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
                .withExposedPorts(8333)
                .waitingFor(Wait.forListeningPorts(8333));
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(GenericContainer<?> seaweedfs) {
        return registry -> registry.add(
                "spring.cloud.aws.s3.endpoint",
                () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333));
    }
}
