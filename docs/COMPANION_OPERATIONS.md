# Companion Server Operations Guide

## Overview

The moqserver companion is a local AI assistant server that runs alongside the mock server. It provides spec analysis, variant generation, and project refinement via structured AI requests while applying redaction policies to protect secrets.

## Prerequisites

- Swift 5.10+
- macOS 12+ or Linux
- At least one AI provider configured (Ollama for local, or OpenAI/Anthropic API keys for hosted)

## Starting the Companion

```bash
# Default: localhost:8081
swift run moqserver companion

# Custom port and hostname
swift run moqserver companion --port 9090 --hostname 0.0.0.0

# With explicit config file
swift run moqserver companion --config ./provider-config.json
```

## Provider Configuration

### Environment Variables

The companion reads provider configuration from environment variables:

**Ollama (local, always enabled by default):**
```bash
export OLLAMA_BASE_URL="http://localhost:11434"  # default
export OLLAMA_MODEL="llama3.1"                    # default
```

**OpenAI (requires API key):**
```bash
export OPENAI_API_KEY="sk-..."
export OPENAI_BASE_URL="https://api.openai.com"   # optional
export OPENAI_MODEL="gpt-4o"                       # default
```

**Anthropic (requires API key):**
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export ANTHROPIC_BASE_URL="https://api.anthropic.com"  # optional
export ANTHROPIC_MODEL="claude-sonnet-4-6"                # default
```

### Config File

Alternatively, pass a JSON config file via `--config`:

```json
{
  "ollama": {
    "baseURL": "http://localhost:11434",
    "defaultModel": "llama3.1"
  },
  "openai": {
    "apiKey": "sk-...",
    "defaultModel": "gpt-4o"
  },
  "anthropic": {
    "apiKey": "sk-ant-...",
    "defaultModel": "claude-sonnet-4-6"
  }
}
```

Environment variables take precedence over config file values.

## Running Alongside the Mock Server

The companion and mock server run on separate ports:

```bash
# Terminal 1: Mock server
swift run moqserver serve --spec ./openapi.yaml --port 8080

# Terminal 2: Companion
swift run moqserver companion --port 8081
```

Or use the Makefile:

```bash
make run        # starts mock server on :8080
make companion  # starts companion on :8081
```

## Health Check

Verify the companion is running:

```bash
curl http://localhost:8081/health
# {"status":"ok","version":"1.0.0"}
```

## Available Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Health check |
| `/ai/providers` | GET | List available providers |
| `/ai/validate-config` | POST | Validate provider configuration |
| `/ai/analyze-spec` | POST | Analyze an OpenAPI spec |
| `/ai/generate-variants` | POST | Generate response variants |
| `/ai/refine-project` | POST | Refine a .moqproj project |

See `docs/COMPANION_API.md` for full request/response contracts.

## Security Considerations

### Binding

By default, the companion binds to `127.0.0.1` (localhost only). This prevents external access to your AI providers and API keys.

**Do not bind to `0.0.0.0` in production** unless you have network-level access control.

### Redaction

The companion applies a redaction engine to all outgoing requests to hosted providers (OpenAI, Anthropic). This strips:

- Bearer tokens and Authorization headers
- API keys
- Cookies and session tokens
- JSON fields with sensitive-looking keys

Local providers (Ollama) skip redaction since data stays on the machine.

### API Keys

- Store API keys in environment variables, not in checked-in config files
- The companion never logs or echoes API keys
- Provider configs with API keys are validated but keys are never forwarded to the client

## Docker Deployment

Add the companion to your `docker-compose.yml`:

```yaml
services:
  moqserver:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - ./spec:/app/spec
      - ./mocks:/app/mocks

  companion:
    build: .
    command: ["moqserver", "companion", "--port", "8081", "--hostname", "0.0.0.0"]
    ports:
      - "8081:8081"
    environment:
      - OLLAMA_BASE_URL=http://ollama:11434
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY}

  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"
```

## Troubleshooting

### Provider unavailable

```
Provider: Ollama (local) — unavailable
```

**Cause:** Ollama is not running or not reachable at the configured URL.
**Fix:** Start Ollama (`ollama serve`) or check `OLLAMA_BASE_URL`.

### Invalid provider config

```
Invalid provider config JSON at ./config.json: ...
```

**Cause:** The config file is malformed or missing required fields.
**Fix:** Validate JSON syntax and check the config format above.

### Hosted provider auth failure

**Cause:** Invalid or expired API key.
**Fix:** Verify your `OPENAI_API_KEY` or `ANTHROPIC_API_KEY` environment variable.

### Redaction policy rejection

**Cause:** The request contains data that triggers the redaction policy in a way that would make the request unusable.
**Fix:** Check the request for embedded secrets and remove them before sending to the companion.
