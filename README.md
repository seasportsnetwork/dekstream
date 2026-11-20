# DekTV

IPTV player for Android tablets and Android TV (Nvidia Shield).

## Testing

Changes go to the `dev` branch first. The **Emulator test** workflow builds a debug APK, runs it on an
Android TV emulator with a public test playlist, screenshots every tab, and fails on crashes or freezes.
Screenshots and logs are pushed to the `smoke-results` branch. When it passes, `dev` is merged into
`main`, which builds the release APK.
