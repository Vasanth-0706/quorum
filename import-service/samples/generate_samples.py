"""Generate the sample upload files in this folder.

    python samples/generate_samples.py

Every student ID is made up (9900000001, 9900000002, ...). Never put real
student data in this folder.
"""
import datetime
from pathlib import Path

import openpyxl

HERE = Path(__file__).parent
FIRST_ID = 9900000001


def save(file_name, sheets):
    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)
    for name, rows in sheets.items():
        sheet = workbook.create_sheet(name)
        for row in rows:
            sheet.append(row)
    workbook.save(HERE / file_name)
    print("wrote", file_name)


def main():
    # 25 clean IDs, stored as numbers the way Excel does it.
    save("shortlist_valid.xlsx", {
        "shortlist": [["studentId"]] + [[FIRST_ID + n] for n in range(25)],
    })

    # Title rows above the header, plus one row for every rejection reason.
    save("shortlist_with_errors.xlsx", {
        "shortlist": [
            ["Sample Corp - round 2 shortlist"],
            [],
            ["S.No", "Reg No", "Name"],
            [1, FIRST_ID, "Student A"],
            [2, float(FIRST_ID + 1), "Student B"],
            [3, " 99000 00003 ", "Student C"],       # spaces: accepted
            [4, "99000004", "Student D"],            # NOT_10_DIGITS
            [5, "99AB000005", "Student E"],          # NOT_NUMERIC
            [6, None, "Student F"],                  # EMPTY
            [],                                      # ignored
            [7, FIRST_ID, "Student A again"],        # DUPLICATE
            [8, FIRST_ID + 7, "Student H"],
        ],
    })

    with open(HERE / "attendance.csv", "w", newline="") as file:
        file.write("student id\n")
        file.writelines(f"{FIRST_ID + n}\n" for n in range(20))
    print("wrote attendance.csv")

    save("timetable.xlsx", {
        "enrolments": [["studentId", "sectionCode"]]
        + [[FIRST_ID + n, "S12"] for n in range(10)]
        + [[FIRST_ID + n, "S13"] for n in range(5, 15)]
        + [[FIRST_ID + 20, "S99"]],                  # UNKNOWN_SECTION
        "slots": [
            ["sectionCode", "courseName", "dayOfWeek", "startTime", "endTime", "room"],
            ["S12", "Cloud Native", "MONDAY", "10:00", "10:50", "C-301"],
            ["S12", "Cloud Native", "WEDNESDAY", datetime.time(11, 0), datetime.time(11, 50), "C-301"],
            ["S13", "Compiler Design", "TUESDAY", "09:00", "09:50", "C-204"],
            ["S13", "Compiler Design", "SATURDAY", "14:00", "15:40", "Lab-2"],
            ["S14", "Data Mining", "SUNDAY", "10:00", "10:50", "C-105"],    # invalid day
            ["S14", "Data Mining", "FRIDAY", "12:00", "11:00", "C-105"],    # start >= end
        ],
    })


if __name__ == "__main__":
    main()
