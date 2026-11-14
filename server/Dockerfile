# Base images are pinned by tag and digest; Dependabot (docker ecosystem) bumps both together.
# The Swift version matches CI and release builds (swift-actions/setup-swift 6.2.4).

# Build stage
FROM swift:6.2.4-jammy@sha256:aa6e815df6d63c995a8e434fa81921295ec7b9dbdf2e5c8d4568935f312c2549 AS builder
WORKDIR /app
COPY Package.swift Package.resolved ./
RUN swift package resolve
COPY Sources/ Sources/
RUN swift build -c release --product moqserver --static-swift-stdlib

# Runtime stage
FROM ubuntu:jammy@sha256:74b12b11ffa550fba1929ad4d8d16533c34f6612728469671afba1b03b66881c
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /app moqserver

WORKDIR /app
COPY --from=builder /app/.build/release/moqserver /usr/local/bin/moqserver

USER moqserver
EXPOSE 8080
# Probes the default port over HTTP, then HTTPS (self-signed certificates accepted) for
# --tls-cert/--tls-key. A container started on another --port needs its own healthcheck.
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || curl -fsSk https://127.0.0.1:8080/health || exit 1
ENTRYPOINT ["moqserver"]
# Mount a .moqproj bundle at /app/project.moqproj (see docker-compose.yml).
CMD ["serve", "--project", "/app/project.moqproj", "--hostname", "0.0.0.0", "--port", "8080"]
