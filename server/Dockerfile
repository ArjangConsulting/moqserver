# Build stage
FROM swift:5.10-jammy AS builder
WORKDIR /app
COPY Package.swift Package.resolved ./
RUN swift package resolve
COPY Sources/ Sources/
COPY Tests/ Tests/
RUN swift build -c release --static-swift-stdlib

# Runtime stage
FROM ubuntu:jammy
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /app moqserver

WORKDIR /app
COPY --from=builder /app/.build/release/Run /usr/local/bin/moqserver

USER moqserver
EXPOSE 8080
ENTRYPOINT ["moqserver"]
# Mount a .moqproj bundle at /app/project.moqproj (see docker-compose.yml).
CMD ["serve", "--project", "/app/project.moqproj", "--hostname", "0.0.0.0", "--port", "8080"]
