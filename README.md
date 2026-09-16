# ManhwaZ Mihon Extension

Mihon/Keiyoushi-compatible source for `https://www.manhwaz.cc`.

## What is included

- Search
- Popular/latest listing
- Manga details
- Chapter list
- Chapter pages/images
- URL/deep-link support

## Build

This module is intended to be copied into a current checkout/fork of the Keiyoushi `extensions-source` repository. The current extension framework requires `KeiSource`/libVersion 1.6.

Copy:

`src/en/manhwaz/`

to the matching path in the current `extensions-source` checkout, then build:

`./gradlew src:en:manhwaz:assembleRelease`

## Repository/index.pb

The APK cannot be represented by a hand-written `index.pb`: Mihon verifies the repository's APK signing certificate. A repository must publish a signed APK and generate its protobuf index. The Keiyoushi repository uses a `repo` branch containing `index.pb`, APKs and repository metadata.

After building/signing the APK, use the current Keiyoushi repository publishing workflow/scripts to generate the index and publish it from your own repository.

## Notes

ManhwaZ currently exposes manga pages under `/webtoon/<slug>` and chapter pages under `/webtoon/<slug>/chapter-...`; chapter reader pages expose page images from `api.manhwaz.cc`. The selectors in this source are deliberately broad because the website's HTML can change.
