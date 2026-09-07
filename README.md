<p align="center">
  <img src="assets/m2-logo.svg" width="144" alt="M² application logo">
</p>

<h1 align="center">M² — MeshCore Live Monitor</h1>

<p align="center">
  See the MeshCore network as it happens.
</p>

M² is a compact Android monitor for the public MeshCore live network. It turns the live packet stream into a readable view of messages, routes, repeaters and signal data — useful both for everyday monitoring and for diagnosing why a packet reached one part of the mesh but not another.

## What it does

- follows the public MeshCore Live API in real time;
- displays packet type, route, hop count, observers, RSSI and SNR;
- shows public keys and packet hashes with one-tap copy actions;
- saves devices by public key and keeps a separate activity log for them;
- supports public channels entered as `#name`;
- supports private channels imported from a 16- or 32-byte key or a QR code;
- decrypts saved private-channel messages locally on the phone;
- highlights mentions in channel messages;
- can keep listening in the background through an Android foreground service;
- includes a real **Close application** action that stops background listening.

Private channel keys are stored with Android Keystore-backed encryption. M² reads the public live API directly and does not require MQTT credentials.

## Download

Install the newest APK from the repository's **Releases** page. Android may ask you to allow installation from your browser or file manager.

## Build from source

Requirements:

- Android Studio or Android SDK 35;
- JDK 17.

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

The generated APK will be available under `app/build/outputs/apk/debug/`.

## Notes

M² is an independent community utility. Reception shown by internet observers does not guarantee that a specific local radio received the same LoRa transmission.

## Support

If M² is useful to you, you can support its development on [Ko-fi](https://ko-fi.com/beepoo304).
