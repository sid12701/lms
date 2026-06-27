#!/usr/bin/env python3
"""Test R2/S3 put using repo-root .env credentials."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def load_env() -> dict[str, str]:
    env: dict[str, str] = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def main() -> int:
    env = load_env()
    endpoint = env.get("APP_STORAGE_DOCUMENTS_R2_ENDPOINT", "")
    access = env.get("APP_STORAGE_DOCUMENTS_R2_ACCESS_KEY", "")
    secret = env.get("APP_STORAGE_DOCUMENTS_R2_SECRET_KEY", "")
    bucket = env.get("APP_STORAGE_DOCUMENTS_R2_BUCKET", "")
    region = env.get("APP_STORAGE_DOCUMENTS_R2_REGION", "auto")
    if not all([endpoint, access, secret, bucket]):
        print("R2 env incomplete", file=sys.stderr)
        return 1

    try:
        import boto3
        from botocore.config import Config
    except ImportError:
        import subprocess

        subprocess.check_call([sys.executable, "-m", "pip", "install", "boto3", "-q"])
        import boto3
        from botocore.config import Config

    client = boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access,
        aws_secret_access_key=secret,
        region_name=region if region != "auto" else "us-east-1",
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )
    key = "loan/debug/test-upload.txt"
    body = b"e2e-r2-probe"
    try:
        client.put_object(Bucket=bucket, Key=key, Body=body, ContentType="text/plain")
        print("R2 put_object OK", bucket, key)
        client.delete_object(Bucket=bucket, Key=key)
        print("cleanup OK")
        return 0
    except Exception as e:
        print("R2 FAILED:", type(e).__name__, e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
