# Ember Launcher for Android

A dark, touch-first Android launcher shell for Minecraft: Java Edition. This repository is an **early MVP**, not a complete Minecraft runtime.

## Included in this MVP

- **Modrinth discovery:** search mods and shader projects using the public Modrinth API, with filters for Minecraft version and (for mods) Fabric, Forge, NeoForge or Quilt.
- **Local file manager:** download `.jar` mod files and `.zip` shader packs into Ember's app-private library. Downloads are restricted to HTTPS Modrinth CDN hosts and capped at 256 MB per file.
- **Offline profiles:** save a local player name for coding/testing.
- **Microsoft device-code sign-in flow:** OAuth device authorization followed by Xbox Live, XSTS, Minecraft entitlement and profile checks. Requires the developer's own app Client ID and appropriate app registration configuration.
- **Ely.by authentication:** posts credentials directly to Ely.by's documented Minecraft authentication API over HTTPS. Passwords and access tokens are not persisted.
- **Mobile UI:** launcher dashboard, version/loader preferences and a touchscreen control-layout preview.
- **GitHub Actions:** builds a debug APK and uploads it as an Actions artifact.

## Not implemented yet (important)

The Minecraft Java engine is **not bundled or connected**. The Play button stays disabled, downloaded mods are not injected into a game, shader packs are not rendered, saved touch settings do not inject gameplay input, and memory preferences do not affect a JVM. Actual game support means integrating and testing a compatible open-source Android Java runtime/renderer (for example, a properly licensed PojavLauncher-derived runtime) and its native libraries. This is deliberately not represented as already working.

No launcher can guarantee “zero lag”: performance depends on the device GPU/CPU, Minecraft version, render distance, mods, renderer and shader pack.

## Build an APK

Requirements: Android SDK Platform 35, Build Tools, JDK 17, and network access for Gradle/Android plugin resolution.

```bash
./gradlew :app:assembleDebug
```

The debug APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The included `gradlew` bootstrap downloads Gradle 8.10.2 on the first run. Android Studio can also open this project and sync/build it after installing the Android SDK.

### GitHub Actions build

Push this project to a GitHub repository. The `Android APK` workflow runs for pushes, pull requests and manual runs. Download `ember-launcher-debug-apk` from the completed workflow's **Artifacts** section.

## Configure Microsoft sign-in

Microsoft identity is not configured by a universal built-in app ID. Register a public-client application in Microsoft Entra, configure the supported device-code/mobile flow and Xbox Live sign-in capability for your app, and enter that app's **Application (client) ID** on Ember's Accounts screen. Never ship a client secret in a mobile app. Actual Xbox/Minecraft sign-in requires the relevant app-registration permissions and a Microsoft account that owns Minecraft: Java Edition. See Microsoft's [mobile sign-in guide](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-mobile-app-sign-in).

## Modrinth API

Catalog requests use the public [Modrinth API v2](https://docs.modrinth.com/api/). Please respect the API's terms, service availability and attribution expectations.

## Ely.by

The login flow targets the documented `https://authserver.ely.by/authenticate` endpoint. Ely.by accounts/credentials are managed by Ely.by, not Ember. See [Ely.by Minecraft authentication docs](https://docs.ely.by/en/minecraft-auth.html).

## Security notes

- Account passwords are not saved.
- Microsoft/Ely.by access tokens are used only in memory for the sign-in flow and are not saved by this MVP.
- Only the current display profile, a local Ely.by client token, and UI preferences are persisted.
- Native downloads require HTTPS and an allowlisted Modrinth CDN hostname.
- Review this code and configure your own identity application before distributing builds.

## License and name

Ember Launcher starter code is MIT-licensed. Minecraft is owned by Mojang Studios/Microsoft; Modrinth and Ely.by are independent services. Ember Launcher is not affiliated with those services. Any future runtime integration must comply with its upstream project's license and third-party license notices.
