# Third-party notices

## ESLyric searcher scripts

Source: https://github.com/ESLyric/scripts

Pinned upstream commit: `32244ac30ffb8f6fd9ff6d03a1dcce389b5c1600`

The 27 searcher JavaScript files listed by that commit's `archive/version.json` are bundled in `app/src/main/assets/eslyric/upstream/searcher/` and included in the APK. Their original file headers and author/source credits are retained.

The upstream repository has no repository-level LICENSE file, and the bundled searcher files do not provide a uniform license grant. The MIT license in the ezXLRC root applies only to ezXLRC's own code; it must not be read as relicensing these scripts. Confirm the relevant authors' redistribution permissions before distributing public APKs containing them.

## Legacy searchers from the user-provided Library archive

Input archive: `eslscripts.zip`

Input SHA-256: `714f2ef3ea1684f4f6be8907ee1bc54044ca44c3eb013db8653af320801ee4a7`

Three non-duplicate searchers are bundled in `app/src/main/assets/eslyric/library-searchers.zip`:

- `kuwo.js` — ESLyric sample search service; the archived source has no explicit license declaration.
- `netease_ex.js` — original author ohyeah, modified by Robotxm; source header declares GPL-3.0.
- `qqmusic_ex.js` — original author ohyeah, modified by Robotxm; source header declares GPL-3.0.

The archived Kugou, Musixmatch, NetEase and QQ Music scripts are superseded by the pinned upstream versions and are not duplicated. The archived parser scripts are not loaded by ezXLRC because KRC/QRC/YRC decoding is implemented natively in Kotlin. The archive's `lib/parser_ext.so` was excluded: it is a PE32+ Windows x86-64 DLL, not an Android shared library.

The full GPL-3.0 license text is included at `LICENSES/GPL-3.0.txt`. The GPL-3.0 notices apply to the two Ex searcher files, not to the rest of ezXLRC.

## QuickJS Android wrapper

Package: `wang.harlon.quickjs:wrapper-android:3.2.3`

Source: https://github.com/HarlonWang/quickjs-wrapper

The Android wrapper is distributed under Apache License 2.0; its license text is included at `LICENSES/Apache-2.0.txt`. The embedded QuickJS engine is based on Fabrice Bellard's QuickJS project: https://bellard.org/quickjs/

## jsoup

Package: `org.jsoup:jsoup:1.18.3`

Source: https://github.com/jhy/jsoup

Licensed under the MIT License. The license text is included at `LICENSES/MIT-jsoup.txt`.

## QQ Music QRC decoder reference

The Kotlin QRC decoder was independently implemented with Android/JCE APIs after consulting the public algorithm reference in WXRIW/QQMusicDecoder:

https://github.com/WXRIW/QQMusicDecoder

That reference repository is licensed under the MIT License. The reference's copyright and license notice are reproduced below.

Copyright (c) 2023 WXRIW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
