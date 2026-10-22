package com.example.basstranscription;
import org.springframework.stereotype.Component;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;
@Component
class EventListener {
 private final JobService jobs;
 private final JsonMapper json=JsonMapper.builder().build();
 EventListener(JobService jobs) { this.jobs=jobs; }
 @RabbitListener(queues=Messaging.EVENTS) public void receive(Message message) {
  try { jobs.apply(json.readValue(message.getBody(),JobService.Event.class)); }
  catch(DataAccessException ex) { throw new ImmediateRequeueAmqpException("Database unavailable",ex); }
  catch(Exception ex) { throw new AmqpRejectAndDontRequeueException("Invalid result event",ex); }
 }
}
