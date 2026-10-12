"""import-service: Excel/CSV uploads -> validation -> report in MySQL -> Kafka."""
import datetime
import io
import logging
import os
import uuid
from contextlib import asynccontextmanager

import openpyxl
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response

import config
import models
import parsing
import publisher
import validation

log = logging.getLogger("import-service")

XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

TEMPLATES = {
    "shortlist": {"shortlist": ["studentId"]},
    "attendance": {"attendance": ["studentId"]},
    "timetable": {
        "enrolments": ["studentId", "sectionCode"],
        "slots": ["sectionCode", "courseName", "dayOfWeek", "startTime", "endTime", "room"],
    },
}


@asynccontextmanager
async def lifespan(app):
    models.init_db()
    try:
        publisher.ensure_topic()
    except Exception as error:
        # Start anyway: uploads answer 503 until Kafka is back, and the topic
        # check is repeated before the first publish.
        log.warning("Kafka is not reachable at %s: %s", config.KAFKA_BOOTSTRAP, error)
    yield


app = FastAPI(title="import-service", lifespan=lifespan)


@app.exception_handler(parsing.ParseError)
def parse_error_handler(request, error):
    return JSONResponse(status_code=400, content={"detail": str(error)})


def read_upload(file):
    """Return (file_name, content) after checking type and size."""
    file_name = os.path.basename(file.filename or "")
    content = file.file.read(config.MAX_FILE_BYTES + 1)
    parsing.check_file(file_name, content)
    return file_name, content


def finish_upload(upload_type, target_id, file_name, total_rows, accepted_count, rejected,
                  build_events):
    """Save the report, publish the events and answer with the report.

    build_events(upload_id, uploaded_at) returns the list of events to send.
    """
    upload_id = str(uuid.uuid4())
    uploaded_at = datetime.datetime.now().replace(microsecond=0)

    # Saved before publishing and only upgraded to PUBLISHED once Kafka has
    # confirmed, so a report never claims a delivery that did not happen.
    status = "PUBLISH_FAILED" if accepted_count else "NOTHING_TO_PUBLISH"
    models.save_report(models.ImportReport(
        upload_id=upload_id,
        type=upload_type,
        target_id=str(target_id),
        file_name=file_name[:255],
        total_rows=total_rows,
        accepted_count=accepted_count,
        rejected_count=len(rejected),
        rejected=rejected,
        status=status,
        uploaded_at=uploaded_at,
    ))
    if not accepted_count:
        return JSONResponse(status_code=422, content=models.get_report(upload_id))

    try:
        publisher.publish(target_id, build_events(upload_id, uploaded_at.isoformat()))
    except publisher.PublishError as error:
        log.error("Upload %s could not be published: %s", upload_id, error)
        return JSONResponse(status_code=503, content=models.get_report(upload_id))

    return models.set_status(upload_id, "PUBLISHED")


def student_id_upload(upload_type, target_id, file, build_event):
    file_name, content = read_upload(file)
    rows = parsing.extract(parsing.read_rows(content, file_name), parsing.STUDENT_ID_COLUMNS)
    student_ids, rejected = validation.validate_student_ids(rows)
    return finish_upload(
        upload_type, target_id, file_name, len(rows), len(student_ids), rejected,
        lambda upload_id, uploaded_at: [build_event(upload_id, target_id, student_ids, uploaded_at)],
    )


@app.post("/imports/shortlist")
def upload_shortlist(file: UploadFile = File(...), roundId: int = Form(...)):
    return student_id_upload("SHORTLIST", roundId, file, publisher.shortlist_event)


@app.post("/imports/attendance")
def upload_attendance(file: UploadFile = File(...), sessionId: int = Form(...)):
    return student_id_upload("ATTENDANCE", sessionId, file, publisher.attendance_event)


@app.post("/imports/timetable")
def upload_timetable(file: UploadFile = File(...), semester: str = Form(...)):
    semester = semester.strip()
    if not semester:
        raise HTTPException(status_code=400, detail="semester must not be empty")

    file_name, content = read_upload(file)
    slot_rows = parsing.extract(
        parsing.read_rows(content, file_name, "slots"), parsing.SLOT_COLUMNS, "the 'slots' sheet")
    enrolment_rows = parsing.extract(
        parsing.read_rows(content, file_name, "enrolments"), parsing.ENROLMENT_COLUMNS,
        "the 'enrolments' sheet")

    slots, rejected_slots = validation.validate_slots(slot_rows)
    sections = {slot["sectionCode"] for slot in slots}
    enrolments, rejected_enrolments = validation.validate_enrolments(enrolment_rows, sections)

    return finish_upload(
        "TIMETABLE", semester, file_name,
        len(slot_rows) + len(enrolment_rows),
        len(slots) + len(enrolments),
        rejected_slots + rejected_enrolments,
        lambda upload_id, uploaded_at: publisher.timetable_events(
            upload_id, semester, slots, enrolments, uploaded_at),
    )


# Declared before /imports/{uploadId} so "templates" is not taken for an id.
@app.get("/imports/templates/{type}")
def download_template(type: str):
    sheets = TEMPLATES.get(type.lower())
    if sheets is None:
        raise HTTPException(
            status_code=404, detail="Unknown template: use shortlist, attendance or timetable")

    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)
    for name, headers in sheets.items():
        workbook.create_sheet(name).append(headers)
    buffer = io.BytesIO()
    workbook.save(buffer)
    return Response(
        content=buffer.getvalue(),
        media_type=XLSX_MEDIA_TYPE,
        headers={"Content-Disposition": f'attachment; filename="{type.lower()}_template.xlsx"'},
    )


@app.get("/imports/{uploadId}")
def get_report(uploadId: str):
    report = models.get_report(uploadId)
    if report is None:
        raise HTTPException(status_code=404, detail="Upload not found")
    return report
