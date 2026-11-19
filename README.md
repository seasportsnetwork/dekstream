# DekTV

DekTV is an IPTV player for Android TV, Google TV and Fire TV (built for the Nvidia Shield).
It plays the sources you add (Xtream, M3U, Stalker portals) and ships with no channels or content.

**DekTV is based on [OwnTV](https://github.com/ahXN00/OwnTV)** by ahXN00 and contributors, and keeps its
GPLv3 license. Thank you to the OwnTV project — see [NOTICE.md](NOTICE.md) for what DekTV changes.

## Install

Download the latest APK (it always points to the newest build):

```
https://github.com/seasportsnetwork/dekstream/releases/latest/download/DekTV.apk
```

On a TV, enter that address (or your Downloader short code) in the Downloader app.

## Building

The GitHub Actions workflow in `.github/workflows/build.yml` builds a signed release on every push to
`main`. Locally: JDK 25 and the Android SDK, then `./gradlew assembleStandardRelease`.

Core libraries resolve from OwnTV's public Maven repository (`tv.own.owntv:core`, `:player-core`,
`:libmpv`), built from [OwnTV_Core](https://github.com/ahXN00/OwnTV_Core) and
[OwnTV_libmpv](https://github.com/ahXN00/OwnTV_libmpv).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE). DekTV, like OwnTV, must stay GPLv3 and its
source available. OwnTV's name and logo belong to the upstream project; DekTV uses its own branding.

## Legal

DekTV is a media player only. It does not provide channels, playlists or subscriptions. You are
responsible for the sources you add and for following the laws that apply to them.
