package com.example.ppps.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9094}")
    private String bootstrapServers;

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "ppps-group");
        config.put(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class
        );
        config.put(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            JsonDeserializer.class
        );
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Trust all packages so any event class can be deserialized
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        // Use the type header written by the producer for deserialization target
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);
        config.put(JsonDeserializer.REMOVE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(
            config,
            new StringDeserializer(),
            new JsonDeserializer<>(Object.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<
        String,
        Object
    > kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // Enable batch-error handling: failed records go to DLQ after retries
        factory.getContainerProperties().setMissingTopicsFatal(false);
        return factory;
    }
}
