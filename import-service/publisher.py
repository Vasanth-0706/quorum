"""Build the import events and publish them to Kafka.

Values are plain JSON (camelCase, ISO dates) with no type headers; the Java
consumers map them onto their own classes.
"""
import json
import logging
import uuid

from confluent_kafka import KafkaError, KafkaException, Producer
from confluent_kafka.admin import AdminClient, NewTopic

import config

log = logging.getLogger(__name__)

DELIVERY_TIMEOUT_MS = 15_000

_producer = None
_topic_ready = False


class PublishError(Exception):
    """Kafka did not confirm delivery of every message."""


def encode(event):
    return json.dumps(event, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def shortlist_event(upload_id, round_id, student_ids, uploaded_at):
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "SHORTLIST_UPLOADED",
        "uploadId": upload_id,
        "roundId": round_id,
        "studentIds": student_ids,
        "uploadedAt": uploaded_at,
    }


def attendance_event(upload_id, session_id, student_ids, uploaded_at):
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "ATTENDANCE_UPLOADED",
        "uploadId": upload_id,
        "sessionId": session_id,
        "studentIds": student_ids,
        "uploadedAt": uploaded_at,
    }


def timetable_events(upload_id, semester, slots, enrolments, uploaded_at,
                     max_bytes=config.MAX_EVENT_BYTES):
    """One event, or several if it would be bigger than max_bytes.

    Only the enrolments are split; the slots travel in part 1.
    """
    def event(part, total_parts, part_slots, part_enrolments):
        return {
            "eventId": str(uuid.uuid4()),
            "eventType": "TIMETABLE_UPLOADED",
            "uploadId": upload_id,
            "semester": semester,
            "part": part,
            "totalParts": total_parts,
            "slots": part_slots,
            "enrolments": part_enrolments,
            "uploadedAt": uploaded_at,
        }

    # Measure with part numbers wider than any real one, so the final events
    # can only be smaller than what was counted here.
    widest = 10**9
    size_without_enrolments = len(encode(event(widest, widest, [], [])))
    size = len(encode(event(widest, widest, slots, [])))

    chunks = [[]]
    for enrolment in enrolments:
        enrolment_size = len(encode(enrolment)) + 1  # + the comma
        if chunks[-1] and size + enrolment_size > max_bytes:
            chunks.append([])
            size = size_without_enrolments
        chunks[-1].append(enrolment)
        size += enrolment_size

    return [
        event(number, len(chunks), slots if number == 1 else [], chunk)
        for number, chunk in enumerate(chunks, start=1)
    ]


def ensure_topic():
    """Create the topic with 3 partitions if it does not exist yet."""
    global _topic_ready
    if _topic_ready:
        return
    admin = AdminClient({"bootstrap.servers": config.KAFKA_BOOTSTRAP})
    if config.TOPIC not in admin.list_topics(timeout=5).topics:
        topic = NewTopic(config.TOPIC, num_partitions=config.TOPIC_PARTITIONS, replication_factor=-1)
        try:
            admin.create_topics([topic])[config.TOPIC].result(timeout=10)
            log.info("Created topic %s with %d partitions", config.TOPIC, config.TOPIC_PARTITIONS)
        except KafkaException as error:
            if error.args[0].code() != KafkaError.TOPIC_ALREADY_EXISTS:
                raise
    _topic_ready = True


def _get_producer():
    global _producer
    if _producer is None:
        _producer = Producer({
            "bootstrap.servers": config.KAFKA_BOOTSTRAP,
            "acks": "all",
            "enable.idempotence": True,
            # Give up (and report it) instead of delivering long after the
            # upload was already answered with PUBLISH_FAILED.
            "message.timeout.ms": DELIVERY_TIMEOUT_MS,
        })
    return _producer


def publish(key, events):
    """Send the events and wait until Kafka has confirmed every one of them."""
    errors = []
    confirmed = []

    def on_delivery(error, message):
        if error is not None:
            errors.append(str(error))
        else:
            confirmed.append(message.offset())

    try:
        ensure_topic()
        producer = _get_producer()
        for event in events:
            producer.produce(config.TOPIC, key=str(key).encode("utf-8"), value=encode(event),
                             on_delivery=on_delivery)
        not_delivered = producer.flush(DELIVERY_TIMEOUT_MS / 1000 + 5)
    except (KafkaException, BufferError) as error:
        _get_producer().purge()
        raise PublishError(str(error)) from error

    if not_delivered:
        producer.purge()
        raise PublishError(f"{not_delivered} message(s) were not confirmed in time")
    if errors or len(confirmed) != len(events):
        raise PublishError("; ".join(errors) or "delivery was not confirmed")
