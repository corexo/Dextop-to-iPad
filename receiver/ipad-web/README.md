# Dextop iPad Receiver (MVP)

This is a local-network iPad receiver shell for the first Wi‑Fi remote-display prototype.

## What works now

- Plays Dextop's low-latency local `live.mp4` stream.
- Sends touch (`down/move/up/cancel`), wheel scroll, and keyboard `down/up` events back to Android.
- Uses a short-lived token (`X-Dextop-Token`) and protocol version `v:1`.

## How to run on iPad

1. Start Dextop on Android.
2. In **Features → Status → Remote iPad display (MVP)** tap **Start remote mode**.
3. Copy the `pairing payload` JSON.
4. Open this `index.html` on iPad Safari (for example from a local static server), paste payload, tap **Apply payload**, then **Connect**.

## Limitations (MVP)

- Receiver is a Safari web companion, not a native iOS app target yet.
- Video transport uses the existing fragmented-MP4 compatibility streamer, not WebRTC yet.
- Keyboard mapping currently relies on browser `keyCode` values.
- Single-pointer touch injection only.
