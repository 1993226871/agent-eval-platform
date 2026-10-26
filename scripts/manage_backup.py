"""Offline PostgreSQL + S3 backup/restore. Restore never drops or overwrites existing data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def manage(mode, env_file, directory, extra_files=()):
    directory = Path(directory).resolve()
    compose = ["docker", "compose", "--env-file", str(Path(env_file).resolve()), "-f", str(ROOT / "deploy/compose.yaml")]
    for file in extra_files: compose += ["-f", str(Path(file).resolve())]
    def run(*arguments, **options):
        return subprocess.run(compose + list(arguments), cwd=ROOT, check=True, **options)
    def database_query(sql):
        return subprocess.check_output(compose + ["exec", "-T", "postgres", "psql", "-U", "agenteval", "-d", "agenteval", "-Atc", sql], cwd=ROOT).decode().strip()
    if mode not in {"backup", "restore"}:
        raise ValueError("Unknown backup operation")
    # This requires an explicit operator maintenance operation; no automatic restart hides partial failure.
    run("stop", "api", "worker")
    if mode == "backup":
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        with (directory / "database.dump").open("xb") as output:
            run("exec", "-T", "postgres", "pg_dump", "-U", "agenteval", "-d", "agenteval", "--format=custom", "--no-owner", "--no-acl", stdout=output)
        objects = directory / "objects"; objects.mkdir(mode=0o700)
        uid = str(os.getuid()) + ":" + str(os.getgid()) if hasattr(os, "getuid") else "10001:10001"
        run("run", "--rm", "--no-deps", "--user", uid, "--volume", directory.as_posix() + ":/backup", "storage-init", "backup", "/backup/objects")
        digest = hashlib.sha256((directory / "database.dump").read_bytes()).hexdigest()
        (directory / "backup.json").write_text(json.dumps({"version": 1, "database_sha256": digest}), encoding="utf-8")
    else:
        manifest = json.loads((directory / "backup.json").read_text(encoding="utf-8"))
        if manifest.get("version") != 1 or hashlib.sha256((directory / "database.dump").read_bytes()).hexdigest() != manifest["database_sha256"]:
            raise ValueError("Invalid database backup")
        if database_query("select count(*) from information_schema.tables where table_schema='public'") != "0":
            raise ValueError("Restore destination database must be empty; nothing was dropped")
        # Storage helper verifies the full manifest and rejects a nonempty bucket before uploading.
        uid = str(os.getuid()) + ":" + str(os.getgid()) if hasattr(os, "getuid") else "10001:10001"
        run("run", "--rm", "--no-deps", "--user", uid, "--volume", directory.as_posix() + ":/backup:ro", "storage-init", "restore", "/backup/objects")
        with (directory / "database.dump").open("rb") as source:
            run("exec", "-T", "postgres", "pg_restore", "-U", "agenteval", "-d", "agenteval", "--exit-on-error", "--no-owner", "--no-acl", stdin=source)
    return directory


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["backup", "restore"])
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    manage(args.mode, args.env_file, args.directory)
    print("Operation complete. API and Worker remain stopped; verify data before explicitly starting them.")
