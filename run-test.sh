#!/usr/bin/env bash

export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')
export TESTCONTAINERS_RYUK_DISABLED=true
# export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')

export ARGOCD_RESOURCE_TIMEOUT=180

mvn clean test