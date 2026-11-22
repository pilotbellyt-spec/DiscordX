# DiscordX architecture

## Usage

1. In Phoenix Settings, select **DiscordX Plugins** in the Account Settings list.
2. Find a plugin by name or description and change its switch.
3. ReviewDB loads reviews on supported profile previews. Clean Links applies at the browser boundary.

## Shape

The build tool adds narrowly scoped Android calls for activity lifecycle, keys, render batches, view events, browser and clipboard links, paint, motion settings, and already-delivered Gateway messages. The bootstrap loads the independently compiled DEX from a signed APK asset and validates its interface version. Rebuilding plugin code can reuse the patched host DEX.

`PhoenixRender` reads Phoenix's decoded render transaction and emits additional nodes in the same format. `NativeDiscordUi` finds Settings and profile anchors by semantic identifiers in the transaction. `NativeSettings` uses the host navigator. `NativeReviews` creates its modal, review rows, and avatars through the host renderer and fetches review data on a worker thread. ReviewDB sign-in uses a WebView restricted to the service's OAuth flow. MessageLogger stores earlier versions in memory and attaches them to chat messages. The renderer replaces a marker with styled history, and the composer and clipboard remove it.

## Alternatives

- A local Dex hook can sit at the existing Android UI boundary. It requires an APK build for each compatible Phoenix release and works without a runtime injector.
- An Xposed module could deliver similar customization without re-signing Discord. The target VM has no such framework installed, and installing one adds a guest-wide dependency just to load this mod.
- A Rust renderer hook could be useful if a full desktop layout path is found. Shared GPUI-native strings alone don't establish that capability. This version uses the Android window contract.
