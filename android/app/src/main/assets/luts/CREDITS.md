#HaldCLUT Presets

* **Black and White** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **Instant (consumer)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **Instant (pro)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **Negative (color)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **Negative (new)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **Negative (old)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))
* **PictureFX** (by [Marc Roovers](http://www.digicrea.be/haldclut-set-style-a-la-nik-software/))
* **Print films** (by [Juan Melara](http://juanmelara.com.au/print-film-emulation-luts-for-download))
* **Slide (color)** (by [Pat David](http://blog.patdavid.net/2013/08/film-emulation-presets-in-gmic-gimp.html))

<a rel="license" href="http://creativecommons.org/licenses/by-sa/4.0/"><img alt="Creative Commons License" style="border-width:0" src="https://i.creativecommons.org/l/by-sa/4.0/88x31.png" /></a><br />This work is licensed under a <a rel="license" href="http://creativecommons.org/licenses/by-sa/4.0/">Creative Commons Attribution-ShareAlike 4.0 International License</a>.

*The trademarked names which may appear in the filenames of the HaldCLUT images are there for informational purposes only. They serve only to inform the user which film stock the given HaldCLUT image is designed to approximate. As there is no way to convey this information other than by using the trademarked name, we believe this constitutes fair use. Neither the publisher nor the authors are affiliated with or endorsed by the companies that own the trademarks.*



Converted from HaldCLUT level 8 to 33^3 RGB8 (trilinear) by tools/convert_clut.py; source af7b50d4caf6244fb6895a647f5b6a84efe7931a.


#CCD 数码相机（digicam/）

Eight CCD compact cameras of 2002-2009 from [Marcs Digicam Emulator](https://github.com/marc-gronenberg/marcs-digicam-emulator) by Marc Gronenberg, MIT License (digicam/LICENSE). The colour part of each camera (its 37^3 LUT over 13 stops of scene exposure) converted to 33^3 sRGB by tools/convert_digicam.py; the emulator's sensor noise, blooming, fringing, sharpening and noise reduction are ported to C++ in android/app/src/main/cpp/digicam.cpp.

#Movie looks, Editor Hub originals, community picks (editorhub/)

35 LUTs from [Editor Hub](https://editor-hub.com/assets/luts): its Originals, its Movie looks and the FreshLUTs community picks it hosts, all released under CC0 1.0 (editorhub/LICENSE). Two community picks carrying a third party's notice (Celluloïd, CineStill 800T) are left out. Converted to 33^3 by tools/convert_editorhub.py.

#Retro cameras (retro/)

26 retro camera looks (CCD compacts, disposable and point-and-shoot film cameras, instant film, black and white) from [ganjmeng/dazz-retro-camera](https://github.com/ganjmeng/dazz-retro-camera) (flutter_app/assets/lut/cameras, commit c8a7f26), MIT License, Copyright (c) 2026 DAZZ Team (retro/LICENSE; full text in third_party/dazz-retro/LICENSE). The project generates them with its own scripts from per-camera colour parameters. Converted to 33^3 by tools/convert_dazzretro.py.
