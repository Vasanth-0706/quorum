"""Build small in-memory upload files for the tests."""
import io

import openpyxl


def xlsx(sheets):
    """sheets: {sheet_name: [row, row, ...]} -> .xlsx bytes."""
    workbook = openpyxl.Workbook()
    workbook.remove(workbook.active)
    for name, rows in sheets.items():
        sheet = workbook.create_sheet(name)
        for row in rows:
            sheet.append(row)
    buffer = io.BytesIO()
    workbook.save(buffer)
    return buffer.getvalue()


def student_rows(cells):
    """[cell, cell] -> rows as parsing.extract returns them, starting at Excel row 2."""
    return [(number, {"studentId": cell}) for number, cell in enumerate(cells, start=2)]
