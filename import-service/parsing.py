"""Turn an uploaded .xlsx / .csv file into rows of named cells.

Nothing here judges the data; it only finds the header row and keeps track of
the row number the user sees in Excel.
"""
import csv
import io
import os
import re

import pandas as pd

import config

ALLOWED_EXTENSIONS = (".xlsx", ".csv")

# Accepted header names, already normalised (lower case, no spaces/underscores).
STUDENT_ID = {"studentid", "id", "regno", "registrationnumber", "rollno"}
STUDENT_ID_COLUMNS = {"studentId": STUDENT_ID}
ENROLMENT_COLUMNS = {"studentId": STUDENT_ID, "sectionCode": {"sectioncode"}}
SLOT_COLUMNS = {
    "sectionCode": {"sectioncode"},
    "courseName": {"coursename"},
    "dayOfWeek": {"dayofweek"},
    "startTime": {"starttime"},
    "endTime": {"endtime"},
    "room": {"room"},
}


class ParseError(Exception):
    """The file itself is unusable; the message is shown to the user (HTTP 400)."""


def check_file(file_name, content):
    """Check extension and size. Returns the lower-case extension."""
    extension = os.path.splitext(file_name or "")[1].lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise ParseError("Unsupported file type: only .xlsx and .csv files are accepted")
    if len(content) > config.MAX_FILE_BYTES:
        raise ParseError("File is too large: the maximum size is 5 MB")
    if not content:
        raise ParseError("File is empty")
    return extension


def normalise_header(value):
    return re.sub(r"[\s_]+", "", str(value)).lower()


def is_empty(value):
    if value is None:
        return True
    if isinstance(value, float) and value != value:  # NaN
        return True
    return isinstance(value, str) and value.strip() == ""


def read_rows(content, file_name, sheet=None):
    """Return every row of the file as a list of raw cells, starting at Excel row 1.

    `sheet` is a sheet name (matched like a header); None means the first sheet.
    """
    if file_name.lower().endswith(".csv"):
        if sheet is not None:
            raise ParseError(f"A .csv file has no '{sheet}' sheet: upload an .xlsx file")
        return _read_csv(content)
    return _read_xlsx(content, sheet)


def _read_csv(content):
    try:
        text = content.decode("utf-8-sig")
    except UnicodeDecodeError:
        text = content.decode("latin-1")
    # The csv module copes with title rows that are shorter than the data rows.
    return [list(row) for row in csv.reader(io.StringIO(text, newline=""))]


def _read_xlsx(content, sheet):
    try:
        workbook = pd.ExcelFile(io.BytesIO(content), engine="openpyxl")
    except Exception as error:
        raise ParseError("Could not read the file: it is not a valid .xlsx workbook") from error

    with workbook:
        sheet_name = workbook.sheet_names[0]
        if sheet is not None:
            names = {normalise_header(name): name for name in workbook.sheet_names}
            if normalise_header(sheet) not in names:
                raise ParseError(f"Sheet '{sheet}' not found in the workbook")
            sheet_name = names[normalise_header(sheet)]
        # header=None keeps every row, so list index + 1 is the Excel row number.
        frame = pd.read_excel(workbook, sheet_name=sheet_name, header=None, dtype=object)
    return frame.values.tolist()


def extract(rows, columns, where="the file"):
    """Find the header row and return [(excel_row_number, {field: raw_cell})].

    `columns` maps a field name to the set of normalised headers accepted for it.
    Fully empty rows are skipped.
    """
    header_index, positions = _find_header(rows, columns, where)
    result = []
    for index in range(header_index + 1, len(rows)):
        row = rows[index]
        if all(is_empty(cell) for cell in row):
            continue
        record = {
            field: row[position] if position < len(row) else None
            for field, position in positions.items()
        }
        result.append((index + 1, record))
    return result


def _find_header(rows, columns, where):
    for index, row in enumerate(rows[: config.HEADER_SEARCH_ROWS]):
        headers = [None if is_empty(cell) else normalise_header(cell) for cell in row]
        positions = {}
        for field, accepted in columns.items():
            for position, header in enumerate(headers):
                if header in accepted:
                    positions[field] = position
                    break
        if len(positions) == len(columns):
            return index, positions
    raise ParseError(
        f"Header row not found in the first {config.HEADER_SEARCH_ROWS} rows of {where}: "
        f"expected column(s) {', '.join(columns)}"
    )
