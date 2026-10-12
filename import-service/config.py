"""Settings, read from environment variables only."""
import os

KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "localhost:9092")

DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = int(os.environ.get("DB_PORT", "3306"))
DB_USER = os.environ.get("DB_USER", "root")
DB_NAME = os.environ.get("DB_NAME", "quorum_import")
DB_PASSWORD = os.environ.get("DB_PASSWORD")  # no default on purpose

TOPIC = "import-events"
TOPIC_PARTITIONS = 3

MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_EVENT_BYTES = 900 * 1024
HEADER_SEARCH_ROWS = 10
