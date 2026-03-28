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
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /app/.build/release/Run /usr/local/bin/moqserver

EXPOSE 8080
ENTRYPOINT ["moqserver"]
CMD ["serve", "--spec", "/app/spec/openapi.yaml", "--hostname", "0.0.0.0", "--port", "8080"]
