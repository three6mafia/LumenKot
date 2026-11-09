# Third-party components

- Original Lumen source and artwork — falcofemoralis, MIT (see LICENSE).
- AndroidX Activity, Fragment, Lifecycle and Media3 — Android Open Source Project, Apache-2.0.
- Kotlin and kotlinx.coroutines — JetBrains, Apache-2.0.
- OkHttp and Okio — Square, Apache-2.0.
- Jsoup — Jonathan Hedley, MIT.
- Firebase Android SDK and Google Play services tasks — Google; see individual artifacts and their licenses/terms.
- JUnit — Eclipse Public License 1.0.

Dependency versions are pinned in app/build.gradle.kts. The app does not bundle the Expo or React Native runtime.

- Lucide icon path data — Lucide Contributors, ISC; copied from lucide-react-native 0.563.0 used by original Lumen. See docs/LUCIDE-LICENSE.

- FFmpegKit Maintained (`dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.7`) and FFmpeg — LGPL-3.0; source: https://github.com/ffmpegkit-maintained/ffmpeg (release 8.1.7). Dynamically linked, unmodified native libraries; no GPL codec variant. The AAR includes `res/raw/license.txt` and `res/raw/source.txt`. Published ABIs: arm64-v8a and x86_64.
- Smart Exception Java/Common 0.2.1 — Taner Sener, BSD-3-Clause; FFmpegKit runtime dependency. Source: https://github.com/tanersener/smart-exception.
