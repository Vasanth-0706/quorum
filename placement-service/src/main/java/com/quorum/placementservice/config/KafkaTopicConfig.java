package com.quorum.placementservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic driveEventsTopic() {
        return TopicBuilder.name(Topics.DRIVE_EVENTS).partitions(3).replicas(1).build();
    }
}
