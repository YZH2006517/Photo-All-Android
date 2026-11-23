# LibRaw (third-party)

The engine reads camera RAW files that are not DNG (Nikon NEF, Canon CR2/CR3, Sony ARW,
Fujifilm RAF, Olympus ORF, Panasonic RW2 and others) with [LibRaw](https://www.libraw.org),
copyright 2008-2021 LibRaw LLC, which includes code from Dave Coffin's dcraw.c.

- Used under the **Common Development and Distribution License (CDDL) 1.0**, one of LibRaw's
  two licences (the other is LGPL 2.1). See `LICENSE.CDDL` and `COPYRIGHT`.
- The files under `libraw/`, `src/` and `internal/` are LibRaw's own, copied unmodified by
  `tools/sync_libraw.py` (version in `REVISION`). They remain under CDDL 1.0; the source is
  available here and at https://github.com/LibRaw/LibRaw. The rest of Photo All keeps its own
  licence.
- Only unpacking is used (open, unpack, metadata); demosaicing, colour and everything after
  are the engine's own (`engine/src/camera_raw.cpp`). DNG files never go through LibRaw.
