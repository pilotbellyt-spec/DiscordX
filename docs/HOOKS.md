# Phoenix 356207 hook map

| Boundary | What it can support | Evidence |
| --- | --- | --- |
| Android activity after `setContentView` | In-client plugin panels, controls and local preferences | DiscordX attachment hook |
| Activity key dispatch | Optional quick actions and keyboard shortcuts before the host processes the keys | DiscordX Plugins and ReviewDB shortcuts |
| `onProvideKeyboardShortcuts` | Listing mod shortcuts in Android's keyboard shortcuts UI | JADX shows Phoenix registers existing shortcuts here |
| Native Android content and window dimensions | Rendering tweaks, resize-aware controls, pointer and focus changes | The Android root forwards width, height and density to the Rust host |
| Custom-tab URL handoff | Tracking cleanup and other opt-in local URL rules | Clean Links runs immediately before `Intent.setData` in Phoenix's browser handler |
| `onNewIntent` and Android route dispatch | Commands that open routes Phoenix already understands | JADX shows Android intent handoff; verify individual route behavior before using it |
| Public HTTPS APIs | Independent features that render in Phoenix without needing Discord's session | ReviewDB uses the [public ReviewDB API](https://github.com/mantikafasi/ServerGo#reviewdb) on the host used by the [Vencord plugin](https://github.com/Vendicated/Vencord/blob/main/src/plugins/reviewDB/reviewDbApi.ts) |
| Android-to-Rust event bridge | Existing commands and UI events with a verified message format | JADX shows `GuestHost` receives encoded shortcut and resize payloads |
| Incoming Gateway dispatch | Inline MessageLogger | MessageLogger recognizes JSON and ETF on the Discord Gateway socket. It associates an opaque marker with edited text and retains supported deletions through a local message update. Phoenix's Android text view turns the marker into history |
| Android HTTP bridge | Earlier messages and profile identity | The response hook reads message lists and profile responses on known Discord API routes. The module keeps message snapshots in memory and reuses a verified profile ID for ReviewDB |
| Native drawable and text properties | Local custom themes | Solid fills, gradient stops, and text colors cross identified Android rendering hooks. Custom Themes maps these locally and retains the platform's account and catalog behavior |
| Phoenix WASM entry points | Engine compatibility check | Build 356207 launches its guest via `nativeCreateLinked`. In an isolated Android diagnostic on the VMware guest, the exact x86_64 library loaded, but `nativeCreate(Bridge, byte[])` rejected a valid empty WASM module with `this library was built without a WASM engine (libdiscord.runtime=native)` |
| Rust layout and workspace code | Replacing the underlying mobile panes with desktop panes | No runnable full desktop layout path was verified in this APK |

Build-time DEX hooks call DiscordX at runtime. The x86_64 Rust ELF has one guarded three-byte patch that makes the Phoenix access evaluator return `Some(true)`; the density split remains byte-for-byte identical. The reusable host DEX cache means adding a plugin to an existing hook usually only rebuilds DiscordX's own DEX.
