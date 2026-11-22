# DiscordX architecture

DiscordX patches integration calls into Discord Phoenix, then loads a Java plugin runtime and independently compiled plugin packages. The current host target is Discord Android build **356207**.

## Startup and packages

`src/build.py` patches lifecycle, input, network, renderer, clipboard, link, color, and motion boundaries. The signed APK contains the bootstrap and `assets/discordx/module.dex`. The bootstrap checks its interface version before loading the runtime.

Built-ins and imports use the same package format: `manifest.json`, one `classes.dex`, and optional `assets/`. Built-in implementations live under `src/plugins/`, outside the runtime module. Package validation checks metadata, API/build/capabilities, ZIP limits, paths, duplicate entries, DEX headers, and hashes.

The manager stores complete ZIPs under content hashes in app-private storage. It stages and atomically moves package files before changing the selected package. Android 8+ loads each plugin through `InMemoryDexClassLoader`. The plugin loader shares platform classes and the public `discordx.api`, not the runtime module or Discord internals.

## Lifecycle and cleanup

A plugin implements `start(PluginContext)` and optional `stop()`. Its scope owns hooks, pages, profile/message actions, commands, managed tasks, and cleanup functions. Stopping closes registrations in reverse order and cancels managed work. Completed work does not deliver callbacks to a stopped scope.

The manager records disabled, starting, running, stopping, and failed states. Importing a new or replacement package leaves it disabled. A plugin failure records the error and removes its registrations. An interrupted startup is disabled on the next process launch. A replacement that fails during startup restores the previous package selection.

The host owns local text-token cleanup so disabled plugins do not leave opaque markers in copied or composed text. Plugin preferences persist. MessageLogger history and ReviewDB credentials remain session state.

## Hooks and interface

`DiscordXModule` routes host calls through a shared dispatcher. Plugins register typed listeners and filters through `discordx.api.Hooks`. The host owns Gateway connection tracking and JSON/ETF conversion. Request interception and body filters run at the Android HTTP boundary.

`PhoenixRender` reads decoded render transactions. `NativeDiscordUi` recognizes supported Settings/profile anchors, inserts host-owned controls, and routes their events. `PluginUi` exposes declarative settings and pages, profile actions, verified-message selection actions, and commands without exposing obfuscated renderer classes.

The management page provides file/HTTPS imports, search, an enabled filter, details, errors, enable/disable, reload, removal of imports, and disable-all. Feature pages such as ReviewDB are registered by their plugin packages.

## Boundaries

Plugins execute in Discord's process with its Android permissions and data access. SDK isolation prevents accidental internal dependencies; it is not a security sandbox. Package hashes identify bytes, not authors.

Managed cleanup cannot forcibly stop arbitrary plugin-created threads or undo arbitrary side effects. Native crashes, exhausted memory, and process termination cannot be isolated like ordinary callback exceptions.

New plugins can use existing capabilities without rebuilding Discord. New interception points still require a host build. See the [plugin API](PLUGIN_API.md) and [host compatibility](HOST_COMPATIBILITY.md).
