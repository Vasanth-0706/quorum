"""The upload report table and the few queries the service needs."""
import datetime

from sqlalchemy import JSON, URL, DateTime, Integer, String, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column

import config

_engine = None


class Base(DeclarativeBase):
    pass


class ImportReport(Base):
    __tablename__ = "import_reports"

    upload_id: Mapped[str] = mapped_column(String(36), primary_key=True)
    type: Mapped[str] = mapped_column(String(20))
    target_id: Mapped[str] = mapped_column(String(100))
    file_name: Mapped[str] = mapped_column(String(255))
    total_rows: Mapped[int] = mapped_column(Integer)
    accepted_count: Mapped[int] = mapped_column(Integer)
    rejected_count: Mapped[int] = mapped_column(Integer)
    rejected: Mapped[list] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(20), index=True)
    uploaded_at: Mapped[datetime.datetime] = mapped_column(DateTime)

    def to_dict(self):
        return {
            "uploadId": self.upload_id,
            "type": self.type,
            "targetId": self.target_id,
            "fileName": self.file_name,
            "totalRows": self.total_rows,
            "acceptedCount": self.accepted_count,
            "rejectedCount": self.rejected_count,
            "rejected": self.rejected,
            "status": self.status,
            "uploadedAt": self.uploaded_at.isoformat(timespec="seconds"),
        }


def database_url():
    if config.DB_PASSWORD is None:
        raise RuntimeError("DB_PASSWORD is not set: export it before starting the service")
    return URL.create(
        "mysql+pymysql",
        username=config.DB_USER,
        password=config.DB_PASSWORD,
        host=config.DB_HOST,
        port=config.DB_PORT,
        database=config.DB_NAME,
    )


def init_db(url=None):
    """Connect and create the tables. The database itself must already exist."""
    global _engine
    _engine = create_engine(url or database_url(), pool_pre_ping=True, pool_recycle=3600)
    Base.metadata.create_all(_engine)


def save_report(report):
    with Session(_engine) as session:
        session.add(report)
        session.commit()
        session.refresh(report)


def set_status(upload_id, status):
    with Session(_engine) as session:
        report = session.get(ImportReport, upload_id)
        report.status = status
        session.commit()
        return report.to_dict()


def get_report(upload_id):
    with Session(_engine) as session:
        report = session.get(ImportReport, upload_id)
        return report.to_dict() if report else None
