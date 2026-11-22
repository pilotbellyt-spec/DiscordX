# Phoenix 356207 UI survey

The x86_64 split contains a 114,148,160-byte Android ELF named `liblibdiscord_wasm_host.so`. Ghidra identified the binary as x86-64 and found Discord JNI exports, `android_app`, and GPUI strings. It found no matches for `x11rb` or `smithay_client_toolkit` in the imported memory blocks. The [survey script](PhoenixUiSurvey.java) can be rerun with Ghidra's headless analyzer.

The binary also contains Rust source-path strings under `crates/gpui-native` and `crates/wasm-app/src/screens/workspace`, as well as a reference to `settings-tablet`. The shared UI types, telemetry labels, and tablet settings strings don't demonstrate a switch to the full desktop client. The survey didn't establish a runnable desktop layout path in this APK.

JADX 1.5.6 decompiled `com.discord.nativeapp.MainActivity` from the base APK. The Android activity reports the view dimensions and density to the Phoenix host, places its native root with `setContentView(View)`, and already dispatches keyboard events. Apktool confirmed those method boundaries in smali. DiscordX uses those boundaries for its own Android views and shortcuts.
