# Android Sample App (Jetpack Compose)

This app demonstrates calling `moqserver` from Android.

## What it demonstrates

- `GET /public/health`
- `GET /pets` with selectable `X-Mock-Variant`
- `POST /_auth/token` (`client_credentials`)
- `GET /pets/favorites` with bearer token

## 1) Start moqserver

From the repository root:

```bash
cd server
swift run Run serve \
  --project ../samples/server/showcase.moqproj \
  --config ../samples/server/config.yaml \
  --port 8080
```

## 2) Open and run Android app

Open `samples/android` in Android Studio and run the `app` module.

## 3) Base URL tips

- Android Emulator: `http://10.0.2.2:8080`
- Physical device on same network: `http://<your-mac-lan-ip>:8080`

## Test flow

1. Tap `GET /public/health`.
2. Set variant to `error-500` or `error-503`, then tap `GET /pets`.
3. Tap `Request Token`.
4. Tap `GET /pets/favorites`.
