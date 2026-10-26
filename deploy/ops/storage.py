"""Streaming, checksummed S3 backup; restore requires an empty destination bucket."""
import hashlib
import json
import os
from pathlib import Path
import sys
import boto3
from botocore.config import Config
from botocore.exceptions import ClientError


def client():
    return boto3.client("s3", endpoint_url=os.environ["S3_ENDPOINT"], region_name="us-east-1",
                        aws_access_key_id=os.environ["S3_ACCESS_KEY"], aws_secret_access_key=os.environ["S3_SECRET_KEY"],
                        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}, retries={"max_attempts": 3}))


def initialize(s3, bucket):
    try:
        s3.head_bucket(Bucket=bucket)
    except ClientError as failure:
        if failure.response["ResponseMetadata"]["HTTPStatusCode"] != 404:
            raise
        s3.create_bucket(Bucket=bucket)


def backup(s3, bucket, root):
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if any(root.iterdir()):
        raise ValueError("Backup object directory must be empty")
    records = []
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=bucket):
        for item in page.get("Contents", []):
            name = f"{len(records):08d}.bin"
            digest = hashlib.sha256()
            response = s3.get_object(Bucket=bucket, Key=item["Key"])
            size = 0
            with response["Body"] as stream, (root / name).open("xb") as output:
                while chunk := stream.read(65536):
                    output.write(chunk); digest.update(chunk); size += len(chunk)
            if size != response["ContentLength"]:
                raise ValueError("Incomplete object backup")
            records.append({"key": item["Key"], "file": name, "sha256": digest.hexdigest(), "size": size,
                            "content_type": response.get("ContentType", "application/octet-stream")})
    (root / "manifest.json").write_text(json.dumps({"version": 1, "objects": records}), encoding="utf-8")
    return len(records)


def restore(s3, bucket, root):
    initialize(s3, bucket)
    if s3.list_objects_v2(Bucket=bucket, MaxKeys=1).get("KeyCount", 0):
        raise ValueError("Restore destination bucket must be empty")
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("version") != 1:
        raise ValueError("Unsupported backup version")
    records = manifest["objects"]
    # Verify every local file before performing any remote writes.
    for index, item in enumerate(records):
        if item["file"] != f"{index:08d}.bin":
            raise ValueError("Invalid backup file identity")
        path = root / item["file"]
        if path.is_symlink() or path.stat().st_size != item["size"]:
            raise ValueError("Invalid backup object")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            while chunk := stream.read(65536): digest.update(chunk)
        if digest.hexdigest() != item["sha256"]:
            raise ValueError("Backup checksum mismatch")
    for item in records:
        with (root / item["file"]).open("rb") as stream:
            s3.put_object(Bucket=bucket, Key=item["key"], Body=stream, ContentLength=item["size"], ContentType=item["content_type"])
    return len(records)


if __name__ == "__main__":
    s3, bucket = client(), os.environ.get("S3_BUCKET", "evidence")
    if sys.argv[1:] == ["init"]:
        initialize(s3, bucket)
        print("Storage initialized")
    elif len(sys.argv) == 3 and sys.argv[1] in {"backup", "restore"}:
        count = {"backup": backup, "restore": restore}[sys.argv[1]](s3, bucket, Path(sys.argv[2]))
        print(f"Object {sys.argv[1]} complete: {count} objects")
    else:
        raise SystemExit("Expected init, backup DIRECTORY or restore DIRECTORY")
