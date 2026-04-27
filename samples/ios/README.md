# iOS Sample App (SwiftUI)

This folder contains a minimal iOS app project that demonstrates how to call `moqserver`.

## What it demonstrates

- `GET /public/health`
- `GET /pets` with selectable `X-Mock-Variant`
- `POST /_auth/token` (`client_credentials` grant)
- `GET /pets/favorites` using `Authorization: Bearer <token>`

## 1) Start moqserver

From the repository root:

```bash
cd server
swift run Run serve \
  --spec ../samples/server/openapi.yaml \
  --config ../samples/server/config.yaml \
  --mocks ../samples/server/mocks \
  --port 8080
```

## 2) Open the iOS app in Xcode

1. Open `samples/ios/MoqServerShowcase.xcodeproj` in Xcode.
2. Select an iPhone simulator.
3. Build and run the `MoqServerShowcase` target.

## 3) Base URL tips

- iOS Simulator on same Mac as server: `http://127.0.0.1:8080`
- Physical iPhone on same network: `http://<your-mac-lan-ip>:8080`

## Test flow

1. Tap `GET /public/health`.
2. Select variant `error-500` or `error-503`, then tap `GET /pets`.
3. Tap `Request Token (client_credentials)`.
4. Tap `GET /pets/favorites (Bearer)`.
