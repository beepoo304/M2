# M2 — planned changes

Status: planning notes reviewed on 2026-09-12. These changes have not been implemented or built yet.

1. Rebuild the Live matching logic without adding a traffic-scope selector to the Live screen. Live remains the complete diagnostic view and shows every packet confidently related to any saved key.

2. Classify each Live packet using separate, independently stored relations:
   - `SOURCE · HASH` — a saved key is the decoded packet source;
   - `DESTINATION · HASH` — the packet ends at a saved key;
   - `IN ROUTE · HASH` — a saved key occurs inside a resolved route;
   - `REPORTED BY · HASH` — the exact saved key appears as `observer_id`.
   One packet may contain several relations. A general packet-level `ownTraffic` value must not replace these precise relations. Show a compact green match row on the main Live list, for example `SOURCE · 0652` or `SOURCE · 0652   REPORTED BY · F480`. In packet details, show the complete list under **Tracked key matches**.

3. In Live packet details, show every available observation and route variant related to saved keys. Keep the API route and its reporting observer as separate pieces of data, while also presenting the complete reception chain when useful.

4. Apply exact Live matching rules:
   - use the complete 32-byte public key, represented by 64 hexadecimal characters, whenever the API provides it;
   - use an exact four-character match for a resolved two-byte route hash;
   - match a reporting device only through exact `observer_id`;
   - do not interpret the final route hop as the observer;
   - a one-byte hash alone remains uncertain even when only one currently saved key has that prefix; confirm it only when the API supplies a resolved full key or another exact full-key relation;
   - Live shows all confidently related packets and does not hide them behind a user-selected scope.

5. Put the traffic-mode selector on the map. Every map session remains assigned to exactly one selected saved key and one selected tracking mode. Do not create a global map combining unrelated keys. Changing the key or tracking mode while a session is running must stop that session or require Restart, so different criteria are never mixed.

6. Remove the **Packet type** selector from the map. Map tracking always accepts every packet type. Replace it with four independent tracking modes plus one combined option:
   - `Starts at key` — packets originating from the selected key;
   - `Ends at key` — packets whose complete reception path ends at the selected key;
   - `Related to key` — complete route trees in which the selected key participates;
   - `Reported by key` — packets reported to the active API by the exact selected `observer_id`;
   - `All for selected key` — the union of all valid relations for the selected key.

7. Use **All for selected key** as the default map tracking mode. The selected mode controls both collected map data and Longest Route:
   - `Starts at key`: flight begins at the selected key and follows the longest certain outgoing packet route;
   - `Ends at key`: flight begins at the true packet source and ends at the selected key;
   - `Related to key`: the map shows the complete tree, while Longest Route is one longest chronological certain branch without repeated links, loops, or artificial joining of observations;
   - `Reported by key`: flight begins at the packet source and ends at the selected reporting key; append the observer as the reception endpoint after the final `path_json` hop when coordinates are available;
   - `All for selected key`: choose the longest valid candidate from all four categories and preserve the direction defined by the winning category.
   `Reported by key` works for any RPT, Companion, or observer that actually reports packets to the active API. A device with no matching `observer_id` data simply produces no results in this mode.
   Preserve the existing Longest Route flight presentation and Android screen-recording export exactly as they currently work. Do not redesign its camera movement, north-up orientation, zoom behaviour, labels, full-screen presentation, recording workflow, MP4 output, map style, or M2 logo while implementing the new selection and history rules.

8. In `Related to key` mode, retain complete route trees. Merge duplicate shared edges when drawing and calculating the network.

9. Keep distance calculation based on unique, undirected physical links. Repeated links, shared branches, and the same link travelled in reverse count only once.

10. Update the current **Longest Route** continuously during an active Start–Stop tracking session whenever a longer certain route appears.

11. When Stop is pressed, commit exactly one Longest Route result for that tracking session.

12. Store up to 1000 Longest Route results per key. Each stored record contains only the full key and distance in kilometres. Overwrite the oldest record after reaching the limit.

13. When a new Longest Route record is achieved, colour only its numeric distance (`XXX.XX km`) red. Keep the tile, route line, and title blue.
   Compare a completed session result with the stored results for the same selected key. Results belonging to another key do not determine whether the current result is a new record.

14. Keep the record distance red until the user opens the Longest Route presentation. Then return its colour to blue without deleting the record.

15. Restart clears the current map and tracking session but does not delete saved Longest Route history or exported maps.

16. Keep the existing map counters with these meanings:
   - **Routes** — the number of route variants;
   - **Links** — the number of unique physical connections;
   - **MAX HOPS** — the highest hop count of one route, which does not have to be the longest route in kilometres.

17. Fix restoration of an active map session after minimising the app or after Android recreates its process. Preserve the running session and selected bottom tab. Switching the selected key explicitly stops the previous key's session. Stop and Restart remain the deliberate ways to end or clear a session.

18. Keep **My Log** independent of the tracking mode selected on the map. My Log contains all packets confidently related to saved keys through source, destination, route, exact saved sender name, or an `@mention`. Traffic that only happens to be reported by an observer is excluded. Ambiguous one-byte matches are excluded. Keep the latest 250 entries.

19. Reduce text size by about 20% in:
   - the Live list and packet details;
   - the My Log list and details;
   - the Channels list, channel message view, and channel statistics.
   Do not unnecessarily reduce the bottom navigation or primary buttons.

20. Add two tiles below Channels:
   - rename `Statistics` to **Channel statistics**;
   - add **App packet statistics**.
   App packet statistics must count every unique packet received by the app regardless of map tracking mode or packet type. Persist the cumulative count, its starting date and time, and the total accumulated application running time. Minimise, process recreation, My Log clearing, and API switching must not duplicate or reset the count. Provide an explicit manual Reset action.

21. Do not implement the proposed **Discovered channels** feature.

22. Make saved keys in Settings clickable and show their current neighbours from the active API:
   - node name and hash;
   - `NEIGHBOURS (N)`;
   - the exact active API address as the source;
   - last update time;
   - rows formatted as `HASH · name`, with the observation count such as `266×` aligned in one right-hand column;
   - ellipsise long names rather than breaking the count column;
   - fetch immediately on first open;
   - refresh automatically once per hour;
   - refetch after changing the active API;
   - add a small manual refresh icon with a visible busy state;
   - preserve the last cached result if refreshing fails.

23. Fix **Close app** so one press always closes it completely:
   - remove the task immediately;
   - explicitly stop the foreground listener;
   - cancel outstanding network calls, repositories, and statistics work;
   - use a non-sticky service restart policy;
   - remove the background notification;
   - disable the button after the first press to prevent duplicate actions.

24. Add pull-to-refresh to the Channels list. When a channel moves to the top because of new activity, keep list positioning predictable instead of unexpectedly jumping the visible content.

25. Reduce mobile-data usage while preserving live tracking:
   - normal foreground Live polling: every **3 seconds**;
   - keep WebSocket-triggered refresh behaviour unchanged;
   - Channels automatic network refresh: every **10 minutes**, plus immediately when entering Channels and after manual pull-to-refresh;
   - API health check: every **60 seconds** while online and every **15 seconds** during an outage;
   - packet-detail enrichment: at most **2 concurrent requests**;
   - cache negative packet-detail matches for **5 minutes**;
   - background Live polling without active map tracking: every **30 seconds**;
   - screen-off Live polling without active map tracking: every **60 seconds**;
   - immediately refresh when the app becomes visible again;
   - while a map tracking session is active, poll every **3 seconds**, including while the app is in the background or the screen is off; active tracking overrides the 30-second and 60-second intervals;
   - manual refresh always runs immediately;
   - before changing the intervals, preserve a copy of the previous values so they can be restored easily.

26. Perform a complete audit and correction of saved-key/hash detection and highlighting:
   - compare the complete 32-byte public key, represented by 64 hexadecimal characters, whenever the API provides it;
   - for two-byte routes, require an exact four-character hash match, for example `F480`;
   - do not treat the final route hop as a saved key merely because the packet was received by a saved observer;
   - distinguish four independent cases: a saved key is the source, the destination, occurs in the route, or is only the reporting observer;
   - keep a one-byte hash uncertain unless the API supplies a resolved full key or another exact full-key relation; never confirm it only from the set of currently saved prefixes;
   - colour only the hash that was actually matched to a saved key green;
   - show a confirmed saved observer in a separate green row together with its correct hash, for example `Observed by ... · F480`;
   - never transfer the observer's green highlight to the final route element, such as `86E7`;
   - verify and use the same matching rules in Live, My Log, Channels, Settings, packet counters, and the map;
   - verify Advert, Channel message, Direct message, Request/response, Trace, and zero-hop Ping packets;
   - use the current saved-key set for verification: `0652`, `F480`, and `0A0B`, plus colliding one-byte hashes;
   - `B282` is no longer a saved or tracked key. It may still appear as an ordinary route node, but it must never receive saved-key highlighting or be used as the selected-key relation unless the user adds it again later.

   Verified reference case for the new logic:
   - Live entry: `23:40:44 DTR_cabana_pocket`, packet hash `44481fb1fb4d4940`, packet ID `420467`, type `ADVERT`, with 17 observations on `https://mc.inside.net.pl`;
   - packet source public key: `dc11667c3fab5f3a0bc8ab1da12c99179deb495f217aefcecd2ac619afcb5b49`;
   - the relevant observation was received at `23:40:55` local time by `K-ce Paderewa TBS_OBSx2`;
   - observer full key: `F4809C7817BC777124F638D32008AF197415BA1240C36C186F6EC80619775C68`;
   - observation route: `AAEF -> CABA -> 5261 -> 6548 -> BBFE -> 3702 -> BEBE -> 86E7`;
   - the API resolves `86E7` to the separate full key `86e771b0616afd5d0fb0a9c3c085ad7d1b2e9a2cba0f03d6b1c1572d5e232b17`;
   - `86E7` is the final repeater in `path_json`; `F480` is not a route hop and is supplied separately as `observer_id`;
   - the logical reception chain is `source -> route hops -> 86E7 -> observer F480`, while the displayed MeshCore route must remain the API route and the observer must remain a separate relation;
   - therefore `86E7` must not be coloured green merely because the saved observer `F480` received this route;
   - display the route with normal route colouring and display the confirmed saved observer separately as, for example, `Observed by K-ce Paderewa TBS_OBSx2 · F480` in green;
   - route-node highlighting must be calculated only from confirmed matches against route nodes or their resolved full keys;
   - observer highlighting must be calculated only from an exact match between `observer_id` and a saved full key;
   - source highlighting must be calculated independently from the decoded packet public key;
   - never reuse a general packet-level `ownTraffic` flag to decide which particular route hop should be green. Store and render source matches, route-node matches, destination matches, and observer matches separately.

27. Implement the plan in this dependency order:
   1. Preserve the current APK, current refresh intervals, and baseline behaviour. Use saved keys `0652`, `F480`, and `0A0B` as the main verification set.
   2. Implement point 26 first: one shared key/hash relation engine with separate source, destination, route, and observer matches.
   3. Implement points 1–4: rebuild Live on top of the shared relation engine and verify the `DTR_cabana_pocket` reference packet.
   4. Implement points 5–9: add four map tracking modes plus the combined All option, complete route trees, link deduplication, and correct distance calculation.
   5. Implement points 10–16: make Longest Route depend on the selected map mode, add session results and record history, and preserve Routes, Links, and MAX HOPS semantics.
   6. Implement point 17: restore an active map session, selected key, selected tracking mode, and bottom tab after minimisation or process recreation.
   7. Implement point 25: reduce network usage with the agreed foreground, tracking, background, screen-off, Channels, API-health, and enrichment intervals.
   8. Implement point 18: rebuild My Log matching on the shared relation engine while keeping it independent of the map mode.
   9. Implement point 20: persistent application packet statistics, start date, accumulated running time, deduplication, and manual Reset.
   10. Implement point 22: saved-key neighbours from the active API with hourly and manual refresh plus local cache.
   11. Implement points 24 and 19: Channels pull-to-refresh, stable list position, and the agreed text-size reductions.
   12. Implement point 23: reliable one-press Close app behaviour.
   13. Run integrated verification for all map modes and packet types on both saved APIs, verify background and screen-off behaviour, measure request frequency, then build and install only after an explicit instruction.

   Point 21 remains intentionally excluded because the Discovered channels idea was cancelled.
