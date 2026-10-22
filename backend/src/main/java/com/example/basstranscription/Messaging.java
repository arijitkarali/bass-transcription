package com.example.basstranscription;
import org.springframework.context.annotation.*;
import org.springframework.amqp.core.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @EnableScheduling
class Messaging {
 static final String REQUESTS="bass.jobs.requests", EVENTS="bass.jobs.events", DEAD="bass.jobs.dead";
 @Bean Declarables queues() { return new Declarables(
  QueueBuilder.durable(REQUESTS).deadLetterExchange("").deadLetterRoutingKey(DEAD).build(),
  QueueBuilder.durable(EVENTS).deadLetterExchange("").deadLetterRoutingKey(DEAD).build(),
  QueueBuilder.durable(DEAD).build()); }
}
