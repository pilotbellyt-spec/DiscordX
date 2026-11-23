# DiscordX for Android

DiscordX supports Discord Phoenix build **356207**, **hdpi**, and either **arm64-v8a** or **x86_64**. Most Android phones use arm64. x86_64 builds require an x86_64 Android device or emulator.

Open Phoenix **Settings → DiscordX Plugins**. **Ctrl+Shift+X** also opens the manager from supported Settings screens. Search installed plugins, view their details, and enable or disable them. The bundled plugins start disabled on a fresh installation.

## Manage packages

Select **Import plugin ZIP** to choose a compiled package from Android's document picker. **Import from URL** downloads a direct HTTPS ZIP URL. Redirects are not followed. Imported packages remain disabled until you enable them. Details show the version, author, hash, status, errors, and registered settings.

Reload stops and restarts a plugin. Imports can be removed; built-ins can be disabled. Use **Disable all plugins** when diagnosing a problem. Only enable code you trust: plugins run with Discord's app access.

## Bundled plugins

- **MessageLogger** shows earlier versions below the current message and retains supported single deletions in chat. It tracks text received or loaded while enabled, including DMs. History stays in memory. Multiple-message bulk deletes use Phoenix's normal behavior.
- **Custom Themes** applies local background, text, and accent colors through the Android renderer. Open **Edit palette** in its settings. Applying a palette or disabling the plugin recreates the activity. Discord's theme catalog is separate.
- **ReviewDB** adds **Reviews** to supported profiles. Browse public reviews, avatars, and scores. Sign in to vote, post/edit/delete your review, report reviews, or block reviewers. The login is held in memory and cleared when the plugin stops or the process ends.
- **Clean Links** removes recognized tracking parameters from opened web links and links in new outgoing and incoming messages. Other parameters and fragments remain. Recognized authentication and signed links are preserved.
- **Clean Copied Links** applies the same cleaner to copied single URLs. Other copied text is preserved.
- **Open in Browser** sends eligible HTTP/HTTPS links to the default browser. Recognized authentication routes retain the original intent handling.
- **Reduce Motion** combines its preference with Android's system preference. Reopen the window if Phoenix cached its motion setting.
- **Quick Jump** adds `/quick-jump` to the native server command picker. Enable the plugin, select the command, fill in `link`, and press Send to open the destination locally. If Phoenix already cached the command list, reopen Discord. Plugin settings also accept links.
- **Screen Share Privacy** sets Android's `FLAG_SECURE` on the main window to restrict screenshots and screen sharing while enabled.
- **Copy Profile Link** adds a copy action to supported profiles with a verified user ID.
- **HideTyping** hides other users’ typing indicators.
- **GhostMode** suppresses your outgoing typing notifications. An existing enabled HideTyping 1.x bundled installation migrates to GhostMode.
- **DeArrow** replaces eligible YouTube embed titles and thumbnails with community branding. It sends video IDs to DeArrow. Additional uncached videos in a batch may update when the messages load again.
- **MessageLatency** shows the delay between a message nonce and its server ID for delays of 2 to under 120 seconds. Messages without a timestamp-bearing nonce have no label.
- **NoProfileThemes** removes profile colors, banners, effects, nameplates, avatar decorations, and display name styles locally. Enable **Keep my own profile appearance** to preserve your own cosmetics.
- **Translate** adds a message action that sends the selected message to Google Translate. Set the target language code in plugin settings. It defaults to the device language. For automatic translation of new messages, enter explicit channel IDs in settings. Messages in those channels are sent to Google and translations appear below the original content.
- **FakeNitro** inserts a CDN link when you select a custom emoji in the native composer picker. It also converts explicit emoji markup and unique `:name:` shortcodes when sending.
- **Plugin Example** demonstrates buttons, local replies, message annotations, profile actions, a local echo command, and configurable message transformations.

- **Mention Avatars** adds the mentioned user's avatar to the mention in chat.
- **Text Replace** applies saved literal replacements to outgoing messages. The longest matching phrase wins.
- **NoTelemetry** blocks recognized analytics and error-report POST requests.

In **Custom Themes → Edit palette**, **Theme default profiles** uses the palette background for profiles without personal or server theme colors. **NoProfileThemes** can be enabled separately.

Each built-in is a separate DEX ZIP under `src/plugins/`. See the [plugin API](PLUGIN_API.md).

## Build and install

Requirements are Python 3.11+, a JDK, Apktool 2.12, Android SDK build tools **36.1.0**, Android platform **36**, and `rg` on `PATH`. Use the original base, hdpi, and one CPU split from build 356207. Run commands from the repository root.

```powershell
python src/build.py --out src/dist/android --keystore <local-keystore> --alias <key-alias> <base.apk> <hdpi.apk> <cpu.apk>
```

Set `DISCORDX_SIGNING_PASSWORD` in the build process environment. Signed output contains `base.apk`, `hdpi.apk`, the CPU APK, `module.dex`, and plugin ZIPs with the SDK JAR under `plugins/`. Without a keystore, the script emits an unsigned patched base and returns before native split patching and signing.

For an x86_64 test device:

```powershell
adb install-multiple -r src/dist/android/base.apk src/dist/android/hdpi.apk src/dist/android/x86_64.apk
```

For arm64, use the generated `arm64-v8a.apk` instead. An in-place update requires the installed app's signing identity. Web builds use a separate public development identity and cannot update Play Store Discord or a differently signed local build.

Launch patches default to Phoenix when no client preference is saved, disable the failed-launch fallback to React Native, and alter the local native access evaluator. They do not establish server-side access. The fallback is not a recovery mechanism for a broken Phoenix launch.

## Verify

`src/verify_apks.py` compares split payloads and signatures. `src/verify_plugins.py` builds a separate plugin-runtime verification app:

```powershell
python src/verify_plugins.py
python src/verify_plugins.py --adb <device-serial> --user <android-user-id>
```

The second command installs and clears data only for `discordx.verification`, then runs headless instrumentation under the selected Android user. Omitting `--user` uses the current user. It does not launch an activity, switch users, or change Discord's data. The bounded report is saved to `src/dist/plugin-verification/report.txt`.

The checks exercise package validation, installation, lifecycle, hooks, cleanup, and update behavior. They do not prove every Phoenix screen or remote service works.

`src/verify_http.py` runs headless instrumentation against the real patched Discord HTTP classes. It installs only `discordx.http.verification` and does not launch Discord or switch Android users:

```powershell
python src/verify_http.py --host src/dist/plugin-system/base.apk --adb <device-serial> --user <user-id>
python src/verify_message_logger.py
```

The logger check uses the JVM and a checksum-pinned `org.json:json:20240303` dependency, cached under `src/.cache/`. It exercises message snapshots, edits, local deletions, markers, and lifecycle reset without sending Discord messages.

## Repository layout

- `docs/`: build, architecture, API, compatibility, and investigation notes.
- `src/`: Android runtime, public SDK, plugins, bootstrap, build, and verification tools.
- `web/`: browser builder, prepared payloads, and Cloudflare download proxy.

Generated outputs go in `src/dist/` and the host DEX cache goes in `src/.cache/`; both are ignored by Git.
