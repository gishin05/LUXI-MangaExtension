# Luxi Manga Extensions

First-party extension store for [Luxi](https://github.com/gishin05/Luxi), focused on **Manga, Manhwa, and Manhua** sources.

## Store URL

Add this store URL in Luxi (**Browse → Extensions → Extension stores**):

```
https://raw.githubusercontent.com/gishin05/LUXI-MangaExtension/repo/index.json
```

## Repository Layout

- `core/` — shared AndroidManifest template and base resources
- `lib/` — shared extension utilities
- `lib-multisrc/` — reusable themes (Madara, MangaThemesia, HeanCMS, Keyoapp, Iken, etc.)
- `src/<lang>/<name>/` — individual extension modules
- `.github/workflows/` — PR verification and automatic `repo` branch publishing
- `.github/scripts/` — v2 (`index.json`) and legacy (`repo.json` / `index.min.json`) index generator

## Building Locally

```bash
JAVA_HOME=/home/xezar/android-studio/jbr ./gradlew :src:en:mangadex:assembleDebug
```

## Policy

Extensions only access publicly exposed site endpoints and APIs. CAPTCHA-solving or anti-bot bypass scripts are not permitted.

## License

Licensed under the [Apache License 2.0](./LICENSE). Structure inspired by [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).
