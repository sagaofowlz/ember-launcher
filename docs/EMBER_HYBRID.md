# Ember Hybrid architecture

## Product decision

Ember's original MVP UI is the primary interface. Zalith Launcher 2 / Pojav-derived code is retained underneath for runtime initialization, version management, account sessions, launch tasks, renderer setup, JVM/runtime libraries, game process handling and actual in-game controls. This is an integration branch, not a replacement of Ember's visual design with Zalith's Compose screens.

## First connected slice

- The original MVP HTML/CSS is bundled at `ZalithLauncher/src/main/assets/ember/index.html` and displayed in a WebView overlay.
- The existing Zalith Compose UI remains composed underneath the Ember overlay, allowing native version/account screens and runtime launch dialogs to be revealed on demand.
- Ember Play emits the runtime's existing game-launch event.
- Account and version buttons reveal native runtime screens; offline profile creation uses the runtime account manager.
- Modrinth results are fetched from the public HTTPS API. Compatible files are installed into the selected profile's `mods` or `shaderpacks` directory rather than a separate Ember-only library.
- Memory preference is routed to the runtime JVM-memory setting. Minecraft version and mod-loader fields remain compatibility filters; the installed/selected runtime profile is still managed by Runtime Versions.

## Validation gates

1. GitHub Actions must compile the arm64 Debug APK.
2. Install on an Android device and verify startup, back navigation, runtime account screen, runtime version screen, and vanilla launch.
3. Verify Modrinth installation uses the selected profile's actual directory and rejects a mismatched Minecraft version / loader.
4. Test touch controls using the native runtime control editor. Ember's original control-layout view is currently only a preview.
5. Test shader packs with a compatible shader loader before claiming shader rendering works.

Do not merge into `main` until these gates pass. Preserve GPL-3.0 source and notices as required by the upstream project and its additional terms.