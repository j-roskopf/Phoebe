# projectM patches

| Directory | Applies to | Purpose |
| --- | --- | --- |
| `common/` | every target | `0001-target-framebuffer.patch` — composite the final frame into the framebuffer the host had bound on entry instead of hard-coding FBO 0. iOS/CAEAGLLayer has no usable default framebuffer; the desktop host renders into a texture FBO and reads it back, so both need this. Hosts that draw straight to the window (Android EGL) bind FBO 0 on entry and are unchanged. |
| `ios/` | `ios-sim` / `ios-device` only | GLES headers, CMake iOS support, and SOIL2 GLES3. See `ios/README.md`. |

Applied by `scripts/build-projectm.sh` after it resets the patched files, so
re-runs are idempotent. Do not bump past v4.1.7 unless these overlays become
intractable.
