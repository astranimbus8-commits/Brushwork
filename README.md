# Brushwork

A painting app for Android in the spirit of ibisPaint and Clip Studio Paint. Work in progress.

## Download

Get the latest APK from the [Releases](../../releases) page, open it on your phone and allow
"install from unknown sources" when asked.

## Build

Requires JDK 17 and the Android SDK (platform 37).

```
./gradlew assembleDebug
```

Every push to `main` builds an APK in GitHub Actions (see the workflow run's artifacts); pushing a
`v*` tag publishes a GitHub Release with the APK attached.
