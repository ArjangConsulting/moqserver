#!/usr/bin/env python3
"""Exercise the public CLI framing and cross-process bundle lock, without a shell parser."""
import fcntl
import json
from pathlib import Path
import subprocess
import sys
import tempfile

binary = str(Path(sys.argv[1]).resolve())


def run(*args, data=None, code=0):
    result = subprocess.run([binary, *args], input=data, text=True, capture_output=True, check=False)
    assert result.returncode == code, result.stderr
    if code:
        assert not result.stdout, result.stdout
        return json.loads(result.stderr)
    assert not result.stderr, result.stderr
    return json.loads(result.stdout)


with tempfile.TemporaryDirectory(prefix="moq-author-smoke-") as directory:
    project = str(Path(directory) / "api.moqproj")
    run("project", "create", "--path", project, "--name", "Smoke")
    run("endpoint", "upsert", "--project", project, "--json", "-",
        data=json.dumps({"id": "get-users", "method": "GET", "path": "/users"}))
    variant = {"endpoint_id": "get-users", "name": "success", "status": 200, "default": True, "body": {"ok": True}}
    assert run("variant", "upsert", "--project", project, "--json", "-", data=json.dumps(variant))["outcome"] == "created"
    assert run("variant", "upsert", "--project", project, "--json", "-", data=json.dumps(variant))["outcome"] == "replaced"
    assert run("endpoint", "remove", "--project", project, "--id", "missing", code=1)["code"] == "E_ENDPOINT_NOT_FOUND"
    assert run("endpoint", "remove", code=64)["code"] == "E_INVALID_ARGUMENTS"
    with open(Path(directory) / ".api.moqproj.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        assert run("project", "describe", "--project", project, code=1)["code"] == "E_PROJECT_BUSY"
    assert run("project", "describe", "--project", project)["endpoint_count"] == 1
print("Author CLI smoke checks passed")
