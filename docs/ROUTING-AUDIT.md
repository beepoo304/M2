# M² 0.1.11 — routing audit summary

The audit reviewed ten areas: packet parsing, saved-key identification, Live status and colors, observation updates, map traffic modes, replies, GPS and geometry, distance and Longest Route, sessions and My log, and the complete packet-to-map flow.

## Resulting changes

- **Observation selection:** map criteria apply to each reported observation. A related packet does not grant access to all of its other branches.
- **Key evidence:** one-byte routes are excluded; resolved identities are checked against their original hashes. Colliding hashes cannot be resolved by choosing the nearest repeater.
- **Replies:** a mention of a saved companion can use the specific observation of another saved observer. Untracked observations remain excluded. Reply routes stay orange, do not gain an invented final link to the addressee and never qualify for Longest Route.
- **GPS:** the complete device directory is separate from the list of valid coordinates. NO GPS and purple shortcuts require a known repeater without valid GPS. An unrecognised intermediate hash does not prove a direct link between its surrounding points.
- **Geometry and distances:** drawing and metrics use the same route geometry. Unique physical links are counted once; GPS shortcuts contribute zero kilometres. Original hop indexes survive missing coordinates, and repeated identities are handled without treating nearby repeaters as the same device.
- **Sessions:** map state retains baselines, captured packet identities and pending rechecks. Routing updates revalidate captured packets without clearing the map. A failed API lookup leaves the recheck pending instead of marking it complete.
- **Live and My log:** explicit source, final recorded hop, intermediate hop, reporting observer and reply evidence are kept separate. Saved logs retain reply evidence and include observation details in text export.
- **Flight and recording:** animation follows display-frame timing, and screen recording remains Android-based. Cancellation and recording completion are handled across the full-screen and application lifecycle.

## Validation

The release passed 64 unit tests with no failures or skipped tests. Phone checks confirmed the previously omitted 17:02:31 reply was restored as three reply routes while all 24 existing routes remained. Another phone check confirmed a packet with unrecognised hashes no longer received false NO GPS labels. Saved keys, API settings and channel configuration were compared before and after installation and remained intact.

The maintainer tested the final routing build before requesting publication. These checks cover the repaired scenarios; they do not assert that every broker report contains a complete radio route.
