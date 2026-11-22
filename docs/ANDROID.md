# DiscordX

DiscordX adds plugins to Discord Phoenix for Android build **356207**, packaged here for **x86_64 + hdpi**. Open **Settings** and select **DiscordX Plugins** in Account Settings to open the plugin page. **Ctrl+Shift+X** also works on that screen.

Ten plugins load from DiscordX's module and start off disabled.

- **MessageLogger** shows earlier versions below the current message and marks retained deletions in red in their original chat position. It can track text from messages Phoenix receives or loads while the plugin is enabled, including DMs. History is kept in memory. Bulk deletes of multiple messages use Phoenix’s regular behavior.
- **Custom Themes** applies a device-local background, text, and accent palette to Phoenix's Android renderer. Enable it, open **Edit palette**, and apply three hex colors. Applying or disabling a palette reloads the window. Discord's own theme catalog and Nitro settings are separate.
- **ReviewDB** opens a Phoenix modal from **Reviews** on a supported profile. Browse public reviews, reviewer avatars, and scores. Sign in to ReviewDB to vote, post or edit your review, report reviews, or block a reviewer. The ReviewDB session lasts until you sign out or Discord stops.
- **Clean Links** removes common trackers such as `utm_*`, `fbclid`, and `gclid` from outgoing links opened through the Phoenix custom-tab handler. Other parameters and fragments stay intact. Links with recognized signing or authentication parameters are left as supplied.
- **Clean Copied Links** applies the same cleaner to single URLs copied through Phoenix's clipboard handler. Other copied text is preserved.
- **Open in Browser** sends ordinary web links to the default browser. Authentication routes and native Discord links keep Phoenix's normal handling.
- **Reduce Motion** asks Phoenix's Android motion engine to reduce animations. It honors the system preference and may need the Discord window reopened.
- **Quick Jump** opens a pasted Discord channel, message, or profile link in Phoenix. Expand **Quick Jump** under DiscordX Plugins to paste the link.
- **Screen Share Privacy** hides the Discord main window from Android screenshots and screen sharing while enabled.
- **Copy Profile Link** adds a copy button to supported profile previews.

The desktop UI investigation didn't identify a working switch in this build. [Read the binary survey](NOTES.md).

## Build and install

Requirements are Python 3.11+, a JDK, Apktool 2.12, and Android SDK build tools 36.1.0 with platform 36. Use the three original Discord APK splits from build 356207. DiscordX defaults to Phoenix when no client preference is saved, disables the three-failed-launch fallback to React Native, and forces the x86_64 Phoenix access evaluator to allow the client. These are the two Cinder launch patches needed by this build. DiscordX does not include Cinder's runtime loader, plugins, staff/devtools, telemetry, or branding patches.

```powershell
python src/build.py --out src\dist\rootless-356207 --keystore <local-keystore> --alias <key-alias> <base.apk> <hdpi.apk> <x86_64.apk>
```

Set `DISCORDX_SIGNING_PASSWORD` in the build process environment. Without a keystore, the build emits an unsigned base APK. Use the installed app's signer for in-place updates. Android rejects updates signed with a different key. Once the activity and browser hooks have been built for a base APK, subsequent plugin-only builds reuse the cached host DEX.

Install all three signed splits together where an Android Debug Bridge connection is available. Then open Discord from the app drawer and select **DiscordX Plugins** in Phoenix Settings.

```powershell
adb install-multiple -r src\dist\rootless-356207\base.apk src\dist\rootless-356207\hdpi.apk src\dist\rootless-356207\x86_64.apk
```

If Phoenix shows **No Access**, the signed-in account needs Phoenix access from Discord. Select **Back to the classic app** to return to standard Discord.

The build adds a small Android bootstrap and packages the independently compiled module at `assets/discordx/module.dex`. At launch, the bootstrap checks the module ABI and loads the signed asset in a separate class loader. The output also includes a byte-identical `module.dex` for developers. The Android 8+ loader uses the signed APK asset to keep executable code within Android's normal installation trust. The Phoenix host differs only at its guarded three-byte access-evaluator stub; its updater is preserved. See the [architecture](ARCHITECTURE.md) for how to add a built-in plugin.

## Verify

`src/verify_apks.py` compares the signed outputs with the inputs and checks split signatures. [The hook map](HOOKS.md) records which Android boundaries can support additional plugins.

## Repository layout

- `docs/`: documentation and Phoenix investigation notes.
- `src/`: Android plugin code, bootstrap loader, build and APK verification tools.
- `web/`: browser builder, required DEX payloads, and Cloudflare download proxy.

Run build commands from the repository root unless a command changes directory. Generated Android outputs go in `src/dist/` and the host DEX cache goes in `src/.cache/`; both are ignored by Git.
