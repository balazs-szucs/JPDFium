# Native size experiments

This branch contains only changes with a measured size benefit and a successful
runtime/build check. The experiments below are recorded so future attempts do
not repeat rejected work.

## Accepted

### BCJ filters in the xz native payload

Built into the per-platform natives jars. x86-64 uses `X86Options` and arm64
uses `ARM64Options` before LZMA2. `XZInputStream` decodes the filters without
loader changes; manifests and checksums still describe the raw libraries.

Measured on all staged bundles:

| Payload | Raw XZ | XZ + BCJ |
|---|---:|---:|
| core | 108.52 MiB | 78.28 MiB |
| vips | 66.32 MiB | 46.98 MiB |
| combined | 174.84 MiB | 125.26 MiB |

### Windows arm64 source AOM

The Windows prebuild now builds AOM for both architectures, using
`AOM_TARGET_CPU=generic` on arm64 (the same baseline vcpkg uses there) and
keeping the encoder-only, high-bit-depth-off configuration.

- `aom.dll`: 6.55 -> 5.36 MiB raw
- Windows arm64 vips tar: 6.40 -> 6.18 MiB
- Vips prebuild smoke: passed
- Release: `vips-20261029-116080d`

### libvips `buildtype=minsize`

The vips source build uses `minsize` instead of `release` on POSIX and Windows.
A 2048x2048 benchmark with `VIPS_CONCURRENCY=1` showed no material throughput
regression:

| Operation | release | minsize | delta |
|---|---:|---:|---:|
| jpegsave | 53.1 ms | 53.6 ms | +0.9% |
| tiffsave | 67.8 ms | 68.1 ms | +0.4% |
| webpsave | 118.8 ms | 119.4 ms | +0.5% |
| thumbnail | 53.7 ms | 54.2 ms | +0.9% |
| resize | 59.8 ms | 59.7 ms | -0.2% |

Combined with the AOM change, the full vips prebuild and a release dry run
succeeded. Windows arm64 vips fell from 6.40 to 6.12 MiB; the six release
platforms fell by 0.43-1.48 MiB each.

## Rejected or deferred

### UPX

UPX 5.2.1 does not support Mach-O dylibs (either architecture) or ELF shared
objects (`my_memcpy` failure, including `--force`). PE DLLs are supported, but
after the existing xz packaging the extra saving was about 0.19 MiB for core
Windows x64 and 0.21 MiB for vips Windows x64; Windows arm64 had no net xz
saving. Control Flow Guard DLLs (PDFium, Skia, ICU, MSVC, third-party) reject
UPX. The loader/AV/code-signing risk is not justified.

### ProGuard

ProGuard 7.10 shrink-only saves about 2.5% on `jpdfium.jar` and 4% on
`jpdfium-vips.jar`; optimize mode changes public method modifiers and
obfuscation breaks reflection/native-image metadata. It remains a
non-publishing CI size check, not a jar replacement.

### PDFium official/ThinLTO

Linux arm64, musl arm64 and FreeBSD component artifacts were about 10% smaller
(8.54 -> 7.66 MiB, 8.56 -> 7.72 MiB, 8.84 -> 7.90 MiB). The full matrix is not
usable: Linux x64 needs a sanitizer ignorelist at the toolchain path, Windows
needs a non-incremental linker setup, and Darwin fails on
`CPDF_ImageObject` completeness; static Darwin also fails. Excluded until the
platform build fixes are solved and the full release can pass.

### Windows static qpdf / ICU dedupe

`qpdf30.dll` is about 2.3 MiB and the two ICU DLLs plus data are about 4.1 MiB
raw. Static qpdf requires a separate MSVC/vcpkg link mode; sharing PDFium's ICU
is blocked by two missing exported `UnicodeString` methods and is not proven
safe. No production change was made.

### Static glib/libvips

Potentially 2-5 MiB, but requires a static GLib/GIO build plus a shared vips
wrapper/boundary rewrite. The ABI/loading risk is not justified without a
dedicated build and runtime proof.

### Other probes

- zstd and Brotli produced larger jars than xz.
- Rust `opt-level=z` grew the static library from 9.8 to 11.8 MB.
- advzip saved about 4% of the Java class jars (~41 KB), not worth a release
  step.
- `strip`/`llvm-strip` are already at their safe maximum; `sstrip`-style
  section removal is unsafe for shared libraries.
