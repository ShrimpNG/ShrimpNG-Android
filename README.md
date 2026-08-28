# ShrimpNG

**Invisible Shrimp** — open-source Android proxy client, forked from [v2rayNG](https://github.com/2dust/v2rayNG).

Website: [shrimp.fish](https://shrimp.fish)

ShrimpNG supports Xray/V2Ray protocols (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, and more). It is a general-purpose subscription client — not tied to any single provider.

## What's different from v2rayNG

- **ShrimpNG branding** (`fish.shrimp.ng`)
- **Automatic HWID** — sends `X-HWID`, `X-Device-OS`, `X-Ver-OS`, and `X-Device-Model` on subscription updates (toggle in Settings → Subscription)
- Optional custom HWID override in settings
- Default User-Agent: `ShrimpNG/{version}`

Not included (by design):

- `happ://crypt4/...` encrypted links
- Happ-proxy limited links
- Happ-specific HWID algorithm

## Download

Build from source (see below) or download releases when published.

## Build

Requirements: Android Studio or JDK 17+, Android SDK (API 24+).

```bash
cd V2rayNG
./gradlew assemblePlaystoreRelease
```

APK output: `V2rayNG/app/build/outputs/apk/playstore/release/ShrimpNG_0.1.0_*.apk`

## HWID settings

**Settings → Subscription**

| Setting | Default | Description |
|---------|---------|-------------|
| Send device ID (HWID) | On | Adds device headers to subscription HTTP requests |
| Custom HWID | empty | Optional override; auto uses Android ID (or stored UUID fallback) |

Per-subscription **User-Agent** in subscription edit still overrides the default when set.

## License

ShrimpNG is based on v2rayNG and is licensed under [GPL-3.0](LICENSE).

Upstream: [2dust/v2rayNG](https://github.com/2dust/v2rayNG)

### Disguised build

Passing `-PDISGUISED=true` produces a build that is already wearing the Calculator disguise: the
launcher entry, the icon, the decoy screen and the label Android shows in Settings → Apps and in
the VPN consent dialog are all the calculator's from first launch.

It keeps the same `applicationId` and signing key as a normal build, so the two APKs install over
each other as ordinary updates. The APK is named `FOSS-Calculator_<version>_<abi>.apk`.

### Building both releases

`build-release.sh` builds the standard and disguised APKs and collects both into `releases/`:

```
./build-release.sh            # arm64-v8a
./build-release.sh armeabi-v7a
```

They are the same variant with a different `-PDISGUISED` value, so AGP writes them to the same
output directory and clears it between runs — the script builds them one at a time and copies
each result out, which is why building both by hand needs the same care.

### Third-party assets

The "Calculator" disguise icon is reused from [Fossify Calculator](https://github.com/FossifyOrg/Calculator),
copyright (c) Fossify, licensed under GPL-3.0 — the same licence as this project. The vector
drawables carry the attribution in their file headers, and it is also shown in the app's About
screen.

## Development

The Android project lives in `V2rayNG/`. Xray core is bundled via the `AndroidLibXrayLite` submodule. See the [v2rayNG development guide](https://github.com/2dust/v2rayNG#development-guide--开发指南) for details on rebuilding the core.
