# Third-party notices

This file lists software and materials used by E-Paper NFC, and how they are licensed.

## Original application

**nfc-epaper-writer** by Joshua Tzucker  
https://github.com/joshuatz/nfc-epaper-writer  
License: MIT  
Copyright (c) 2021 Joshua Tzucker

This project is a derivative of that work. Joshua’s copyright notice is retained in `LICENSE`. Thank you for the original app, the WebView editors, and the first Android integration for these panels.

## Android / Kotlin libraries (Apache License 2.0)

- AndroidX AppCompat, Core KTX, ConstraintLayout, WebKit, Lifecycle Runtime KTX
- Google Material Components
- Kotlin standard library
- Kotlinx Coroutines

Apache-2.0 allows use in an MIT-licensed app. The Apache license text is at  
https://www.apache.org/licenses/LICENSE-2.0

## Android Image Cropper

**CanHub/Android-Image-Cropper** (fork of ArthurHub / Edmodo Cropper)  
https://github.com/CanHub/Android-Image-Cropper  
License: Apache License 2.0  
Copyright 2016 Arthur Teplitzki; 2013 Edmodo, Inc.

## JS Paint (optional graphics editor)

The “New – Graphic” screen loads **JS Paint** from https://jspaint.app/  
https://github.com/1j01/jspaint  
License: MIT  
Copyright Isaiah Odhner and contributors

JS Paint is not bundled in this repository; it is fetched at runtime. A copy of its MIT license is on that project.

## WaveShare hardware protocol

The NFC command sequence used to update WaveShare NFC-powered e-paper (`0xCD` vendor frames over ISO 14443-A) is a hardware protocol published in WaveShare’s Android SDK documentation and C demo. This app implements that protocol in original Kotlin. It does **not** ship WaveShare’s compiled `NFC.jar`.

This project is **not affiliated with, endorsed by, or an official product of WaveShare**.

## Not used / not shipped

- WaveShare `NFC.jar` (proprietary bytecode; no OSI license for redistribution) — removed from this tree.
- Proxmark3 `cmdhfwaveshare.c` (GPL-3.0) — not copied into this repository.
