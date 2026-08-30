# Gallery Cleaner

Android Gallery Cleaner with:
- One-by-one photo/video review
- Trash instead of immediate permanent deletion
- Restore from Trash
- Delete selected / Delete all in Trash
- Selection mode
- Sort by newest/oldest/largest/smallest
- Storage filter: Internal / External / All
- Folder filter
- GitHub Actions APK build

## Build on GitHub
Push this repository to GitHub. The workflow in `.github/workflows/build.yml`
builds a debug APK and publishes it as a workflow artifact.

## Local build
Open in Android Studio (Ladybug or newer), let Gradle sync, then:
`./gradlew assembleDebug`

## Notes
Android 11+ uses MediaStore's native trash flag (`IS_TRASHED`), so restoring is safe
and preserves the original media item. On older Android versions the app uses its
own app-private Trash directory and keeps the original relative path in metadata.
