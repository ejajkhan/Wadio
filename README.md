# Wordsmart Vocabulary (Android)

An offline-first Android vocabulary app for the supplied Wordsmart 1 and Wordsmart 2 JSON collections.

## Features

- Home screen with **Wordsmart 1** and **Wordsmart 2**.
- 34 groups per collection; group names map to the supplied `ws1_group_XX.json` and `ws2_group_XX.json` files.
- Full word details: English word, IPA and Bangla pronunciation, part of speech, English and Bangla meanings, synonyms, antonyms, example, Bangla example, and additional examples when present.
- Swipe left/right or use Previous/Next buttons to move between words in a group.
- Add synonyms and antonyms with optional Bangla meanings; remove entries you added or no longer want.
- All edits are saved to the app's private local copy. Original JSON assets in the source project are not modified.
- **Export** creates a `words.zip` containing the two original folder names and all JSON files with your local edits included.
- Works offline; no account, ads, or network permission is needed.

## Source data

The 68 JSON files from the supplied `Wordsmart_Research_Revision-1.zip` are included unchanged under `app/src/main/assets/word_smart_1/` and `app/src/main/assets/word_smart_2/`. The app copies these into private app storage on first launch and edits those copies. Each collection contains 34 groups; the supplied data has 850 Wordsmart 1 entries and 829 Wordsmart 2 entries.

## Build on GitHub Actions

1. Create a new GitHub repository and upload/push the contents of this project (the files in this folder, not the outer ZIP).
2. Ensure the default branch is named `main` or `master`.
3. Push a commit. The workflow at `.github/workflows/android.yml` builds a debug APK on each push or pull request to those branches.
4. Open the repository's **Actions** tab, select **Build Wordsmart Android App**, open the completed run, and download the **Wordsmart-debug-apk** artifact.
5. Extract the artifact ZIP and install `app-debug.apk` on an Android device. For Android's security prompt, allow installation from the app used to open the APK.

You can also start a build manually from **Actions → Build Wordsmart Android App → Run workflow**.

## Build locally

Requires JDK 17 and Android SDK platform 35. The included `gradlew` / `gradlew.bat` scripts bootstrap the pinned Gradle 8.9 distribution automatically on first run; an existing system Gradle installation is not required. Internet access is needed on first run.

Linux/macOS:

```bash
./gradlew assembleDebug
```

Windows:

```bat
gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The project includes `settings.gradle.kts`, root and app `build.gradle.kts`, `gradle.properties`, a pinned Gradle version in `gradle/wrapper/gradle-wrapper.properties`, and cross-platform bootstrap scripts.

## Notes

- This is a debug build configuration, intended for testing and personal use. A release APK for distribution should be built with a private signing key; never commit that key or its passwords to GitHub.
- Export writes the JSON files using the same relative directory layout as the input archive. It exports the full collection data, including edits, rather than just the currently selected group.
- The app edits the local copy of the supplied JSON data. If you reinstall after clearing app data, any unsaved-to-export edits in app storage are lost; export regularly for backup.

## Open-source UI references

The interface takes general inspiration from common open-source vocabulary/flashcard patterns, including list-first navigation and swipeable word cards. References reviewed during implementation:

- https://github.com/tianjianfeng/VocabApp
- https://f-droid.org/en/packages/vocabletrainer.heinecke.aron.vocabletrainer/

No source code from these projects is bundled into this app.


## Latest feature update

- Dark theme is the default; change to light theme in Settings.
- Settings contains Export and Import actions. Import accepts only a ZIP containing all 68 group JSON files with the exact `word_smart_1/ws1_group_01.json` through `ws1_group_34.json` and `word_smart_2/ws2_group_01.json` through `ws2_group_34.json` paths. The ZIP is validated before replacing local data.
- Bookmark any word from its word-detail screen. Bookmarks persist locally, can be filtered within a group, and are available from the home screen.
- Android system back and edge-back gestures navigate to the previous app screen.
- The app remembers the last open screen, collection, group, and word index.
- Launcher icon uses the `Wrd` mark.

The UI direction references open-source Compose vocabulary apps such as [VocabApp](https://github.com/tianjianfeng/VocabApp) (multiple lists, word navigation, progress memory) and [Palabra](https://github.com/Erik-Donath/Palabra) (mobile-first vocabulary UI and dark-theme polish). This project is an independent implementation and does not copy their source code.

## Vocabulary examples display

The word detail screen displays the primary `example` + `example_bn` pair and entries from `examples` as a compact numbered list (`Example 1`, `Example 2`, etc.). If an entry in `examples` repeats the primary English example, it is shown only once. The JSON data itself is not modified by this display formatting.
