# Translating YouTube Wear

Everything the app says lives in one file, `app/src/main/res/values/strings.xml` (English).
A translation is one more file next to it. You don't need to touch any code or build the app.

## Add a language

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<code>/strings.xml`,
   where `<code>` is the language, for example `values-es` (Spanish), `values-fr`, `values-de`.
   For a regional variant use `values-pt-rBR` (Brazilian Portuguese) or `values-zh-rCN`.
2. Translate the text between the tags. Leave the `name="..."` attributes alone.
3. Delete the strings you'd rather leave in English; anything missing falls back to English.
4. Open a pull request, or if you don't use GitHub, open an issue and attach the file.

## Rules

- **Keep the placeholders.** `%1$s`, `%1$d`, `%2$d` are filled in by the app (a count, a
  number of seconds, an error message). Keep them, and you may reorder them to suit your
  language, since the number says which value goes where: `%2$d ... %1$d` is fine.
- **Keep `\n`.** It is a line break. `\'` is an apostrophe and needs the backslash.
- **Plurals.** `<plurals>` needs one `<item>` per form your language uses. See the
  [Android plurals guide](https://developer.android.com/guide/topics/resources/string-resource#Plurals)
  for the quantities (Spanish, for example, uses `one`, `many` and `other`).
- **Space is tight.** Watch screens are small and differ between models, round and square,
  so short beats literal.
- **Capitals.** `Connecting`, `Loading` and `Forced buffering` are shown in capitals by the
  app. Write them in normal case.
- **The comments** in the file say where each string appears.

## Check your translation

Set the watch's language, or the app's language over adb with the watch on wireless debugging:

```
adb shell cmd locale set-app-locales ca.wolfietech.dev.android.ytwear --locales es
```

Use `--locales en-XA` in a debug build to see the pseudo-locale, which accents every string
that comes from `strings.xml` (text that stays plain English is still hard-coded, so please
report it).

## What is not translated

- Video titles, channel names and descriptions come from YouTube.
- The text of some errors comes from yt-dlp and stays in English.
- The "YT" logo and the app name.
