# Host compatibility

The supported host is Discord Phoenix Android build **356207**. Plugin API compatibility and Discord host compatibility are separate checks.

## Build-time discovery

`src/host_discovery.py` searches decoded smali by semantic strings and structural anchors, then runs each patch's guards. Every integration must resolve to one valid target. Unsupported or ambiguous layouts fail the build rather than receive guessed edits.

Discovery still depends on Discord internals. Some guards include obfuscated descriptors, exact register layouts, and instruction sequences. It reduces dependence on source filenames; it does not establish compatibility with other Discord releases.

The host DEX cache includes the original APK, Apktool, build script, and discovery implementation. Changing those inputs invalidates the cached result.

## Native and browser boundaries

The x86_64 native access patch checks ELF architecture, experiment anchors, and expected bytes at a version-specific offset. The arm64 finder locates the evaluator through ELF/string references before applying a guarded replacement. Neither establishes server-side account authorization.

The browser builder is version-pinned. It checks the original DEX hash, applies a prepared host delta, and checks the reconstructed DEX hash. It does not discover new hooks or compile source. Refresh its prepared payloads after changing the runtime, bootstrap, bundled packages, or host patches.

## Runtime adapters

`PhoenixRender` reflects concrete host render classes and fields. `NativeDiscordUi` recognizes supported Settings/profile anchors in decoded transactions. HTTP, Gateway, navigation, and message-identity adapters also depend on the current Android bridge. Semantic screen names do not guarantee support across updates.

Plugins declare `minApi`, `maxApi`, `discordBuilds`, and optional `requires` capabilities. The importer checks them before installation. `PluginContext.host()` exposes the API version, build, and capability set. Declare only builds and capabilities the plugin has been tested against.

The public Java API isolates plugins from runtime implementation classes. Plugins cannot assume that Discord's obfuscated classes, Kotlin runtime, or private renderer internals are available through their class loader.

## Port to another Discord build

1. Inspect the new build's original APK splits and decoded Android bridge.
2. Update discovery anchors and structural guards for changed integration points.
3. Verify native patch guards for each supported CPU.
4. Update runtime adapters and supported screen/message anchors.
5. Rebuild and run plugin lifecycle/package tests on Android.
6. Exercise the actual Phoenix manager, settings pages, profile/message actions, and network hooks.
7. Regenerate the browser delta and bundled payload manifest from the verified build.
8. Add the new build to plugin manifests after checking its required capabilities.

The verification APK tests the runtime independently of Discord. Passing it does not prove Phoenix renderer compatibility, native graphics behavior, account access, or remote-service availability. Source structure alone does not establish a stability or performance advantage over another client mod.
