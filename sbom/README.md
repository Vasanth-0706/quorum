# SBOMs

CycloneDX 1.6 JSON, one per service, runtime dependencies only (test scope excluded).

| File | Regenerate with |
|---|---|
| `placement-service.cdx.json`, `attendance-service.cdx.json`, `timetable-service.cdx.json`, `checkin-service.cdx.json` | `./mvnw -B cyclonedx:makeBom` in the service folder (cyclonedx-maven-plugin 2.9.3, configured in each `pom.xml`) |
| `import-service.cdx.json` | below (cyclonedx-py 9.0.0 over a clean Python 3.12 venv with only `requirements.txt` installed) |

```bash
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/import-service:/src:ro" -v "$PWD/sbom:/out" \
  python:3.12-slim sh -c 'python -m venv /tmp/run && /tmp/run/bin/pip install -q -r /src/requirements.txt &&
  python -m venv /tmp/tool && /tmp/tool/bin/pip install -q cyclonedx-bom==9.0.0 &&
  /tmp/tool/bin/cyclonedx-py environment --of JSON --output-reproducible --mc-type application \
    -o /out/import-service.cdx.json /tmp/run/bin/python'
```

The Maven plugin is not bound to a build phase, so `./mvnw verify` does not rewrite these files.
Regenerate them when dependencies change and commit the result.
