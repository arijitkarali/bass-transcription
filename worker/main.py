import json
import logging
import os
import time
import uuid
import pika
from processor import TranscriptionProcessor

REQUESTS, EVENTS, DEAD = "bass.jobs.requests", "bass.jobs.events", "bass.jobs.dead"
logging.basicConfig(level=logging.INFO)
logging.getLogger("pika").setLevel(logging.WARNING)
processor = TranscriptionProcessor(os.environ["STORAGE_ROOT"])

def connect():
    credentials = pika.PlainCredentials(os.getenv("RABBITMQ_USER", "bass_app"), os.environ["RABBITMQ_PASSWORD"])
    return pika.BlockingConnection(pika.ConnectionParameters(
        host=os.getenv("RABBITMQ_HOST", "127.0.0.1"), port=int(os.getenv("RABBITMQ_PORT", "5672")),
        credentials=credentials, heartbeat=60, blocked_connection_timeout=30))

def consume(channel, method, properties, body):
    try:
        request = json.loads(body)
        job_id = str(uuid.UUID(request["jobId"]))
        relative = request["inputPath"]
        if request["version"] != 1 or not isinstance(relative, str):
            raise ValueError("Invalid request")
    except (ValueError, KeyError, TypeError):
        logging.exception("Invalid request sent to dead-letter queue")
        channel.basic_reject(method.delivery_tag, requeue=False)
        return

    def emit(status, **fields):
        channel.basic_publish(exchange="", routing_key=EVENTS, mandatory=True,
            properties=pika.BasicProperties(content_type="application/json", delivery_mode=2),
            body=json.dumps(dict(version=1, jobId=job_id, status=status, **fields)))

    emit("PROCESSING")
    try:
        size, result = processor.process(relative, job_id)
    except Exception as error:
        logging.exception("Transcription failed for %s", job_id)
        emit("FAILED", errorMessage=str(error)[:2000])
    else:
        emit("COMPLETED", sizeBytes=size, resultPath=result)
    # Only acknowledge after RabbitMQ confirms the durable terminal event.
    channel.basic_ack(method.delivery_tag)
    logging.info("Processed %s", job_id)

if __name__ == "__main__":
    while True:
        connection = None
        try:
            connection = connect()
            channel = connection.channel()
            channel.queue_declare(queue=DEAD, durable=True)
            for name in (REQUESTS, EVENTS):
                channel.queue_declare(queue=name, durable=True, arguments={
                    "x-dead-letter-exchange": "", "x-dead-letter-routing-key": DEAD})
            channel.confirm_delivery()
            channel.basic_qos(prefetch_count=1)
            channel.basic_consume(queue=REQUESTS, on_message_callback=consume)
            logging.info("Waiting for jobs")
            channel.start_consuming()
        except KeyboardInterrupt:
            break
        except pika.exceptions.AMQPError:
            logging.exception("Broker failure; unacknowledged requests will be redelivered")
            time.sleep(3)
        finally:
            if connection and connection.is_open:
                connection.close()
