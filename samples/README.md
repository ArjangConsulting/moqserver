# Mobile Showcase Samples

This directory contains end-to-end examples for using `moqserver` from mobile apps.

## Included samples

- [`server/`](./server): shared OpenAPI spec, config, and mock overlays used by both apps
- [`ios/`](./ios): SwiftUI sample app with a checked-in Xcode project
- [`android/`](./android): Android Studio project (Jetpack Compose)

## Start the sample server

Run this from the repository root:

```bash
swift run Run serve \
  --spec ./samples/server/openapi.yaml \
  --config ./samples/server/config.yaml \
  --mocks ./samples/server/mocks \
  --port 8080
```

## Demo scenarios

1. Call `GET /public/health` (basic request).
2. Call `GET /pets` with `X-Mock-Variant: error-500` or `error-503`.
3. Request a token from `POST /_auth/token`.
4. Use the token for `GET /pets/favorites`.

## Default sample credentials

- Client ID: `sample-client`
- Client Secret: `sample-secret`
- Token returned by server config: `sample-oauth-token`
