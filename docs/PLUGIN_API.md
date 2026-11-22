# Plugin API

A DiscordX plugin is Java or Kotlin code compiled into one DEX file. Its ZIP contains `manifest.json`, `classes.dex`, and optional files below `assets/`. The current contract is **API 1**, Discord build **356207**, and Android 8 or later.

## Build the SDK and example

Run from the repository root with Python, a JDK, and the Android SDK installed:

```powershell
python src/build_plugins.py
python src/build_plugins.py src/examples/clipboard_prefix --out src/dist/example
```

The first command builds the bundled plugins and `src/dist/plugins/discordx-plugin-api.jar`. The second produces `src/dist/example/example.clipboard_prefix.zip`. Import that ZIP through **DiscordX Plugins → Add plugin → ZIP → Choose ZIP**, then enable it to exercise its clipboard hook.

Compile against the public SDK JAR and Android SDK, not the runtime's implementation classes or Discord internals. The supplied source builder uses that isolated classpath.

## Define a package

```text
my_plugin/
  manifest.json
  src/example/plugin/Plugin.java
  assets/optional-file.txt
```

A source manifest looks like this:

```json
{
  "schemaVersion": 1,
  "id": "example.plugin",
  "name": "Example plugin",
  "version": "1.0.0",
  "author": "Plugin author",
  "description": "Adds a prefix to copied text.",
  "entrypoint": "example.plugin.Plugin",
  "minApi": 1,
  "maxApi": 1,
  "discordBuilds": [356207],
  "requires": ["clipboard"]
}
```

`minApi` and `maxApi` refer to the DiscordX plugin API, not Android SDK versions. `discordBuilds` lists supported Discord builds. `requires` lists host capabilities that must exist. The packager adds `dexSha256` to the generated manifest.

IDs contain 2–64 lowercase letters, digits, dots, underscores, or hyphens and start with a letter. The entrypoint is a public class with a public zero-argument constructor.

The importer limits the ZIP to 8 MiB, executable/assets entries to 4 MiB each, the manifest to 16 KiB, expanded contents to 12 MiB, and entry count to 128. It rejects duplicate entries, unsafe paths, unsupported metadata, and invalid DEX headers/checksums. A package hash identifies bytes, not a trusted publisher.

## Register behavior

```java
package example.plugin;

import discordx.api.Hooks;
import discordx.api.PluginContext;

public final class Plugin implements discordx.api.Plugin {
    @Override public void start(PluginContext context) {
        context.register(Hooks.CLIPBOARD, text ->
            text == null ? null : "Example: " + text);
    }
}
```

The runtime calls `start(context)` when enabled and `stop()` when disabled, reloaded, or replaced. `stop()` defaults to a no-op. Every API registration belongs to the plugin's scope and returns a `Registration` that can be closed early. The scope closes its registrations in reverse order after stop, including failed startup cleanup.

`PluginContext` provides the package ID/metadata, host capabilities, app context, current activity, private preferences, packaged assets, UI services, and hook registration. `runOnMain` and `runAsync` are scoped work helpers. Async work runs in the host executor; completion/error callbacks return to the main thread only while the scope remains active. Put extra resource cleanup in `onCleanup`.

| Hooks | Purpose |
| --- | --- |
| `OPEN_LINK`, `BROWSER_INTENT` | Transform opened links and browser intents. |
| `CLIPBOARD`, `DISPLAY_TEXT`, `COMPOSER_TEXT` | Transform copied, displayed, and composed text. |
| `REDUCE_MOTION` | Filter the host motion preference. |
| `FILL_COLOR`, `TEXT_COLOR`, `FOREGROUND_COLOR`, `GRADIENT` | Transform Android renderer colors. |
| `GATEWAY` | Observe connections and transform raw incoming frames. |
| `GATEWAY_JSON` | Transform decoded Gateway dispatches with JSON/ETF handled by the host. |
| `HTTP_REQUEST` | Return a local response for a matching outgoing request, or `null` to continue. |
| `HTTP_RESPONSE` | Observe received response bytes and URL. |
| `HTTP_BODY` | Transform received response bytes. |
| `ACTIVITY`, `NAVIGATION` | Observe activity and supported navigation events. |

Return the original value for data the plugin does not handle. Scope network behavior to known endpoints or event types. Prefer `GATEWAY_JSON` for decoded dispatches; raw frames include protocol data unrelated to messages. New interception points require a host rebuild.

Use `textTokenPrefix()` when adding local display markers. The host retains token stripping after the plugin stops so opaque markers do not reach copied or composed text.

## Add interface controls

`context.ui()` provides these scoped contributions:

- `settings(title, page)` adds an entry to the plugin's details page.
- `profileAction(title, action)` adds an action on supported profiles with a verified user ID.
- `messageAction(title, action)` adds an action to a verified message's text-selection menu.
- `command(name, description, action)` registers an action in the manager and under its name in the composer.
- `open(title, page)` opens a host-rendered page.
- `androidModal(content)` opens a managed Android modal.

A `Page` builds controls through `text`, `heading`, `secondary`, `image`, `input`, `button`, and `toggle`. Use `row`, `column`, `card`, and `divider` to group controls. Layout groups take a consumer of the same controls interface. Declare `ui.layouts` when using these methods. Keep edit state in the plugin and call `refresh()` after asynchronous results. Message actions receive message/channel/author IDs, content, and selected text. They appear only where the host can verify message identity.

Create, refresh, and close UI on the main thread. Use `runOnMain` from background work. UI registration cleanup marshals removal to the main thread. Disabling deactivates callbacks before invoking `stop()` and drains owned resources afterward. Closing a registration removes its contribution. The API does not expose Phoenix's obfuscated renderer classes.

## Extend chat

Use `context.chat()` from the main thread for UI registrations. List the capabilities you use in the manifest.

| Capability | API |
| --- | --- |
| `chat.buttons` | `button(HEADER or COMPOSER, title, resourceIcon, pressed)` places an accessible button beside search or the emoji picker. |
| `chat.commands` | `slashCommand(name, description, args -> ...)` consumes a local command on submission. This local callback does not register a Discord application command. |
| `before_send` | `beforeSend(text -> ...)` returns the outgoing draft. Return `null` to cancel. Local commands execute first. |
| `chat.accessories` | `accessory(CONTENT or TIMESTAMP, messageId -> label)` returns a colored label or `null`. `redraw(messageId)` refreshes that message. `tint(id -> argb)` colors its author and content, and restores host colors when removed. |
| `chat.local_replies` | `reply(messageId, text)` displays a local reply beneath a message. |
| `chat.local_messages` | `localMessage(channelId, text)` displays a local message above the selected channel's composer. |
| `chat.cache` | `selectedChannel()`, `cachedMessage(channelId, messageId)`, and `queryCache(select, arguments...)` read the account's Phoenix SQLite cache. Call these on a worker thread. |

Declare `chat.command_options` for the `slashCommand(name, description, options, execute)` overload. Each `Chat.Option<T>` has a name, a required flag, and a parser. Throw `IllegalArgumentException` with a field error for invalid input. The plugin manager can open a local form for these fields. A single-option command also accepts `/name value` directly. Read a parsed value with `args.get(option)`.

Both command APIs share one namespace. Names must match `[a-z][a-z0-9_-]{0,31}`. A duplicate registration throws `IllegalArgumentException`. Closing the registration releases the name.

A local message or reply returns a handle with `edit(text)` and `close()`. These entries live for the plugin session. Cache queries bind string parameters to `?`, return at most 500 rows and 1 MiB of JSON, and encode BLOB columns as Base64. An empty cache or ambiguous account selection returns no records.

```java
Chat chat = context.chat();
chat.slashCommand("echo", "Show text locally", args ->
    context.runAsync(chat::selectedChannel, channel -> {
        if (channel != null) chat.localMessage(channel, args);
    }, error -> android.util.Log.w("Echo", "Channel lookup failed", error)));
```

## Package Kotlin or an existing DEX

The source builder compiles Java. For Kotlin, compile a self-contained JAR against the public SDK and Android SDK, convert it with Android `d8`, then package its DEX:

```powershell
python src/build_plugins.py <plugin-directory> --dex <path-to-classes.dex> --out src/dist/external
```

Choose a DEX target compatible with the Android versions you support. The class loader shares platform classes and `discordx.api`, not Discord's Kotlin runtime. Bundle the Kotlin standard library and required dependencies in the DEX. `kotlinc -include-runtime` can include the standard library in the input JAR. Supply implementation dependencies as inputs to `d8`; its classpath alone does not package them.

Only one `classes.dex` is supported. Keep output within package limits. This format does not supply native-library loading or multidex plugin loading.

## Update and test

Import a ZIP with the same ID to replace the selected package. The runtime stops the old instance and leaves the replacement disabled. It retains the previous selection for rollback if replacement startup fails. Reload creates a fresh instance. Removing an imported replacement can reveal the bundled package with the same ID.

For Java development, run `python src/build_plugins.py <plugin-directory> --watch --out <synced-directory>`. The watcher compiles the SDK once and replaces the ZIP after each successful build. Import the ZIP once through an Android document provider that supports persistent reads. Choose **Reload ZIP** in plugin details after syncing. DiscordX validates the replacement ID and restarts it if the previous version was enabled.

Build the verification app with `python src/verify_plugins.py`. Run it on an Android test device with `--adb <serial>`. Also exercise the plugin's actual Phoenix screen and external services. Compilation and package validation do not prove UI integration.

Plugins execute inside Discord's process with the app's data and permissions. Class-loader isolation is a dependency boundary, not a security sandbox. Enable only code you trust.
