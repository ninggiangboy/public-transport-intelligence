"""Containers of the compose stack through the Docker SDK, and the Makefile for stateful operations (DOC-45 §2)."""

from __future__ import annotations

import os
import subprocess
import time
from datetime import UTC, datetime

import docker
import httpx

from pti_exp.config import REPO

PROJECT = "pti"


class Compose:
    def __init__(self) -> None:
        self.client = docker.from_env()

    def container(self, service: str):
        return self.client.containers.get(f"{PROJECT}-{service}-1")

    def kill(self, *services: str) -> datetime:
        """SIGKILL, all at once (EXP-01 kills etl-stream and its baseline with one command)."""
        names = [f"{PROJECT}-{s}-1" for s in services]
        at = datetime.now(UTC)
        subprocess.run(["docker", "kill", "-s", "KILL", *names], check=True, capture_output=True)
        return at

    def start(self, *services: str) -> None:
        for service in services:
            self.container(service).start()

    def stop(self, *services: str, timeout: int = 45) -> None:
        for service in services:
            self.container(service).stop(timeout=timeout)

    def pause(self, service: str) -> None:
        self.container(service).pause()

    def unpause(self, service: str) -> None:
        container = self.container(service)
        container.reload()
        if container.status == "paused":
            container.unpause()

    def running(self, service: str) -> bool:
        try:
            container = self.container(service)
        except docker.errors.NotFound:
            return False
        container.reload()
        return container.status == "running"

    def started_at(self, service: str) -> datetime:
        container = self.container(service)
        container.reload()
        return datetime.fromisoformat(container.attrs["State"]["StartedAt"].replace("Z", "+00:00"))

    def restart_count(self, service: str) -> int:
        container = self.container(service)
        container.reload()
        return int(container.attrs.get("RestartCount", 0))

    def image_digest(self, service: str) -> str:
        container = self.container(service)
        return container.image.id

    def make(self, target: str, **variables: str) -> subprocess.CompletedProcess[str]:
        args = ["make", "-C", str(REPO), target] + [f"{k}={v}" for k, v in variables.items()]
        return subprocess.run(args, capture_output=True, text=True, check=True, env=os.environ.copy())

    def up(self, *services: str, env: dict[str, str] | None = None, profiles: tuple[str, ...] = ("*",)) -> None:
        """Recreates services so that a changed .env or environment takes effect."""
        from pti_exp.config import Stack

        command = Stack().compose_command()
        for profile in profiles:
            command += ["--profile", profile]
        command += ["up", "-d", "--no-deps", *services]
        subprocess.run(command, check=True, capture_output=True, env={**os.environ, **(env or {})})


def wait_ready(url: str, timeout: float = 300) -> datetime:
    """Waits for /actuator/health/readiness to answer UP; returns when it did."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            response = httpx.get(url + "/actuator/health/readiness", timeout=3)
            if response.status_code == 200 and response.json().get("status") == "UP":
                return datetime.now(UTC)
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    raise TimeoutError(f"{url} not ready within {timeout} s")
