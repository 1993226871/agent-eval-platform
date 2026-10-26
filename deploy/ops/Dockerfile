FROM python:3.11-slim@sha256:b7a9e5f6a93d6d4a31f129bbd4bdee69efccb6ded0fb230d78aafaf37af98377
COPY requirements.lock /opt/ops/requirements.lock
RUN pip install --no-cache-dir -r /opt/ops/requirements.lock
COPY storage.py /opt/ops/storage.py
COPY judge_stub.py /opt/ops/judge_stub.py
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1
ENTRYPOINT ["python", "-B", "/opt/ops/storage.py"]
