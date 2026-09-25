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
- maps packet routes for tracked device keys and calculates unique network distance;
- presents the longest resolved route as an animated flight and can record it as an MP4;
- can keep listening in the background through an Android foreground service;
- monitors saved brokers, retains outage history and switches to an available alternative after 30 seconds of failed attempts;
- tracks total app running time and listening time separately for each broker;
- includes a compact, color-coded **Guide** in PL, EN, RU, Slovak, Czech, DE and FR;
- includes a real **Close application** action that stops background listening.

Private channel keys are stored with Android Keystore-backed encryption. M² reads the public live API directly and does not require MQTT credentials.

## Download

Install the newest APK from the repository's **Releases** page. Android may ask you to allow installation from your browser or file manager.

**Current release: [M² 0.1.14](https://github.com/beepoo304/M2/releases/tag/v0.1.14).** Install it as an update to retain your settings, saved keys and channels.

## Understand tracked routes

Live identifies a tracked key as **SOURCE**, **LAST RECORDED HOP**, **IN ROUTE**, **REPORTED BY** or **REPLY TO**. These describe the evidence reported by the API; a final recorded hop does not automatically prove delivery to the addressed device.

Map offers **Starts at key**, **Last recorded hop**, **Related to key**, **Reported by key** and **All for selected key**. M² checks each observation separately so an unrelated branch of the same packet does not enter the selected map.

- **Green:** confirmed route segments.
- **Orange:** confirmed reply observations, including a reply to your companion reported by another saved observer. Replies do not contribute to Longest route, and M² does not invent a final link to the addressed key.
- **Blue:** Longest route. Only the distance of a new record turns red.
- **Purple / NO GPS:** a known repeater with no valid coordinates. A visual shortcut through that repeater contributes zero kilometres. A hash missing from the broker's device list is not labelled NO GPS and does not produce a shortcut.

One-byte paths are excluded because their identities are ambiguous. Distance sums unique mapped links rather than counting every repeated observation. Map sessions retain pending packet checks across app restarts and can revalidate captured packets after a routing-rule update.

Open **Guide** for the compact legend, traffic-mode descriptions and operational notes.

## Broker monitoring and statistics

Select a saved **LIVE API** in Settings. M² keeps the latest 250 outage records separately for each saved broker, including outage duration and restoration. If the active broker remains unavailable for 30 seconds, M² checks saved alternatives and switches to a responding API. With no available alternative, retries slow to every three minutes.

**App Packet Statistics** shows the deduplicated packet count, start date, total app running time and accumulated listening time for each broker. These values survive app restarts; Reset begins a new statistics period.

## Record the longest route

1. Open **Map** and select a tracked device key.
2. Start tracking and let M² collect packet routes, or load a previously saved M² map.
3. Tap the blue **Longest route** button.
4. Approve Android's screen-capture prompt. M² opens the full-screen map and starts the flight from the first repeater.
5. The blue line follows the longest unambiguous route. Only the current segment's hop badges and distance remain visible; older badges fade out. The total route distance appears together with the final hop badge. At the end, only the total distance badge remains over the map.
6. When the flight ends, choose **YES** to save the MP4 in the export folder configured under **Settings**. The default location is `Download/M2`.

Only devices with valid coordinates can be placed on the map. A known repeater without GPS keeps its original hop position and is represented by the purple shortcut; its missing distances are excluded from the total. The flight shows **NO GPS RPT** at that segment. Unrecognised intermediate hashes do not create invented links or flights across a gap.

## Routing audit

This release follows a ten-part review of parsing, key identification, Live classification, observation updates, map traffic modes, reply routes, GPS, distances, flight handling and session persistence. Shared policies now drive observation selection, route geometry, GPS labels and session replay. Regression tests cover the repaired cases, including replies received by a different saved observer and known repeaters without GPS.

See the [routing audit summary](docs/ROUTING-AUDIT.md). The release passed **64 unit tests** and phone checks; the final routing build was also tested by the maintainer before publication.

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

Send app issues, questions and suggestions through [Instagram @m2.meshcore.app](https://www.instagram.com/m2.meshcore.app/).

If M² is useful to you, you can support its development on [Ko-fi](https://ko-fi.com/beepoo304).
