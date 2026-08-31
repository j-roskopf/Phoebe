# Plex origin resolution

How Phoebe picks a PMS address, and why it survives a Wi-Fi -> cellular handoff.

## Model

The catalog stores **relative** Plex paths (`/library/parts/...`) with no host and no
token. An absolute URL is produced at the moment it is needed:

- playback — `StreamingPlaybackPolicyHolder.resolvePlaybackUri`, the single chokepoint
  every platform player already used,
- artwork — `bindPlexUrl` via `ArtworkOriginHolder`,
- downloads — `CatalogRepository.boundMediaUrl`.

Nothing persisted holds an address, so nothing persisted can go stale. This is
shuttle2's model (`PlexMediaInfoProvider` builds the URL from a relative
`externalId` at request time).

`PlexConnectionResolver` owns "what is the live base". It is the only component that
reacts to network changes.

## Choosing an origin

python-plexapi's `_chooseConnection` semantics: probe every candidate `/identity`,
then adopt the **best-ranked** origin that answered, not the first responder.
Ranking is local -> direct remote -> relay, with LAN demoted on cellular/metered/VPN.

- Starts are staggered by rank (`ProbeStaggerMs`), as chromatix-app does, so a healthy
  LAN answers before a relay handshake begins.
- Adoption happens exactly once per race. Publishing each improvement rebased artwork
  and the play queue twice.
- Once something answers, better-ranked probes still in flight get `WinnerGraceMs`,
  not their full budget — a closed public `:32400` must not hold back a working relay.
- Every budget is bounded by the caller's `deadlineMs`.

## Network changes

Probe results are keyed by `(serverId, networkFingerprint)`. A successful `/identity`
only proves an origin was reachable *from where we were*, so a fingerprint change
drops the previous network's results, cancels the in-flight race, clears the published
base, and re-resolves. A race that finishes late is discarded by fingerprint.

During playback, a new base re-opens the current stream at its position
(`SimpleAudioPlayer.reopenCurrentStreamOnNewOrigin`) rather than waiting out the
platform player's 20-30s socket timeout.

## Stored-data revisions

`PhoebeAppDataRevision` in `StorageNames.kt`. Bumping it signs the user out once and
wipes, for changes where old rows cannot be migrated meaningfully. The marker is
written only after the wipe succeeds, so a failed reset retries next launch.

## Platform notes

- Android uses `registerDefaultNetworkCallback` (the network the app's sockets use),
  folds `NET_CAPABILITY_VALIDATED` into the fingerprint, and treats VPN as demoting LAN.
- iOS reads `nw_path_monitor` only. Inferring transport from the device's own IPv4
  address reports cellular as Wi-Fi, because `pdp_ip0` holds a carrier-NAT `10.x` address.
- Network wall-clocks use `withNetworkTimeoutOrNull`, which runs on a real-time
  dispatcher. A bare `withTimeoutOrNull` inherits `runTest`'s virtual clock and reports
  every mocked request as a timeout.
