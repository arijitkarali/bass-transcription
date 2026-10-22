package com.example.basstranscription;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
@Component
class OutboxPublisher {
 private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
 private final JdbcTemplate sql; private final RabbitTemplate rabbit;
 private final JsonMapper json=JsonMapper.builder().build();
 OutboxPublisher(JdbcTemplate sql,RabbitTemplate rabbit) { this.sql=sql;this.rabbit=rabbit; }
 @Scheduled(fixedDelay=2000) public void publish() {
  for(var row:sql.queryForList("select id,input_path from outbox limit 20")) {
   try {
    var body=json.writeValueAsBytes(Map.of("version",1,"jobId",row.get("id").toString(),"inputPath",row.get("input_path")));
    var properties=new MessageProperties(); properties.setContentType("application/json");
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    var confirmation=new CorrelationData(UUID.randomUUID().toString());
    rabbit.send("",Messaging.REQUESTS,new Message(body,properties),confirmation);
    if(!confirmation.getFuture().get(5,TimeUnit.SECONDS).isAck() || confirmation.getReturned()!=null)
     throw new IllegalStateException("Broker did not accept and route request");
    sql.update("delete from outbox where id=?",row.get("id"));
   } catch(Exception ex) { log.warn("Job {} remains in outbox: {}",row.get("id"),ex.getMessage()); break; }
  }
 }
}
