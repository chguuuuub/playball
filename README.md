# Ultimate Pool — Android 8-ball game

Landscape Android game prototype with a perspective-styled table, shaded billiard balls, cue aiming, adjustable power, collision response, cushion rebounds, pockets, and a basic computer opponent.

## Build
- Android Studio: open this folder and sync Gradle.
- Requirements: JDK 17, Android SDK 35, Gradle 8.9.
- Command: `gradle assembleDebug`
- APK: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions builds a debug APK on pushes to main/master and on manual dispatch. Download the APK from the workflow run's Artifacts section.

## Controls
Touch and drag from the cue ball to aim and set power; release to shoot.

## Current scope
This is a first playable prototype with perspective rendering, not a full 3D engine. Full 8-ball foul, group assignment, and win-condition logic are planned for the next iteration.
