# projectM iOS GLES patches (v4.1.7)

Upstream `projectM` v4.1.7 hard-rejects iOS when `ENABLE_GLES=ON` and assumes
Linux `GLES3/gl3.h` headers. These patches are applied by
`scripts/build-projectm.sh` for `ios-sim` / `ios-device` targets only
(idempotent: reset patched files, then `git apply`).

| Patch | Purpose |
| --- | --- |
| `0001-allow-ios-gles-cmake.patch` | Allow `CMAKE_SYSTEM_NAME=iOS`; wire `OpenGLES.framework` instead of FindOpenGL's macOS OpenGL path |
| `0002-ios-opengl-headers.patch` | Use `OpenGLES/ES3/*.h` on `TARGET_OS_IPHONE` / `USE_GLES` |
| `0003-soil2-ios-gles3.patch` | SOIL2 GLES3 include path + skip desktop GL fallback when GLES3 is set |

The target-framebuffer overlay lives in `../common/0001-target-framebuffer.patch`
because every host needs it.

Do not bump past v4.1.7 unless these overlays become intractable.
