# Compose Architecture Guidelines

Phoebe's shared UI should keep navigation, state collection, and rendering as separate concerns. The goal is to make large shared surfaces easier to change across desktop, Android, iOS, and web without growing composables that need the entire app as parameters.

## Navigation

- Use Navigation 3 from `commonMain` for app destinations.
- Treat routes as serializable app locations, not as containers for domain models.
- Route arguments should be stable IDs or small enums. Resolve artists, albums, playlists, and tracks from the catalog near the route entry.
- Keep direct back-stack mutation inside the root coordinator or `PhoebeNavigator`. Reusable UI emits intent callbacks such as `onArtist(id)`, `onBack()`, or `onOpenNowPlaying()`.
- Because Nav 3 is still alpha, isolate its APIs behind Phoebe-owned route and navigator types.
- Browser URL/history integration is intentionally separate from the first Nav 3 pass.

## State Holders And UI

- Root/state-holder composables collect flows, derive UI state, and own side effects.
- UI composables take immutable state plus callbacks. They should not take `AppState`, repositories, or a raw navigation back stack.
- Keep UI-local state local when it only affects layout or interaction, such as scroll, focus, animation, and text field state.
- Keep business state in repositories or `AppState`, then map it into route-facing UI state at the root boundary.

## Composable Contracts

- When a composable crosses roughly 8-10 unrelated parameters, group the contract by responsibility before adding more parameters.
- Prefer feature-shaped contracts such as `PlaybackUiState`, `BrowseUiState`, `AuthSetupState`, and matching `Actions` types over large mixed parameter lists.
- Avoid passing a whole component or state holder into child composables when the child only needs a few values or callbacks.
- Reusable layout components should expose slots for variable visual regions instead of accumulating primitive content parameters or boolean mode flags.

## Refactor Rule

When touching a large screen or shell, improve the boundary you are already using:

- New destination: add a `PhoebeRoute` with minimal args, then render it from the shared route display.
- New desktop/player behavior: add it to the relevant state/action contract, not to a long positional parameter list.
- New screen flow side effect: handle it in the root/state-holder layer and keep the UI callback as a user intent.
