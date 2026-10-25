FROM python:3.11-slim@sha256:b7a9e5f6a93d6d4a31f129bbd4bdee69efccb6ded0fb230d78aafaf37af98377
COPY entrypoint.py read_result.py /opt/runner/
USER 10001:10001
WORKDIR /output
ENTRYPOINT ["/usr/local/bin/python", "-I", "-B", "-u", "/opt/runner/entrypoint.py"]
