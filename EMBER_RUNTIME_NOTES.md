# Ember Runtime Foundation

- Base: Zalith Launcher 2, which uses the PojavLauncher Minecraft Java runtime.
- Branding: Ember Launcher / `dev.sagaowl.emberlauncher` application ID.
- Startup notice: â€œUnofficial Modified Versionâ€.
- Upstream license and the upstream English README (including its additional GPLv3 terms) are preserved.
- The app has real Minecraft version launching, renderer and touch-control infrastructure; actual compatibility/performance still depends on device, Minecraft version, renderer, and mods.
- Account note: configure your own public-client Microsoft OAuth Client ID before signing in. Never embed a client secret in an APK. A Microsoft account must own Minecraft: Java Edition for official online play. Ely.by uses third-party-auth server support where available; local/offline accounts follow upstream restrictions.

## Build result

GitHub Actions should build this branch via the upstream `push_ci.yml` workflow. In your repo, open **Actions**, select the newest **Build CI** workflow run for `ember-runtime`, then download the APK from its **Artifacts** section after the run succeeds. This script itself does not compile or test the APK.