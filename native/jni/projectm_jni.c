/**
 * Shared JNI shim for libprojectM (Android + desktop JVM).
 * Call only with a current OpenGL context on the calling thread.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <projectM-4/projectM.h>

#if defined(_WIN32)
/* projectM's Windows build includes <GL/glew.h> and calls through GLEW's
 * function pointers. The host must glewInit() after the context is current —
 * LWJGL's GL.createCapabilities() does not initialize GLEW, and leaving the
 * pointers NULL access-violates on the first projectM GL call. */
# include <GL/glew.h>
#endif

static projectm_handle handle_from(jlong ptr) {
    return (projectm_handle)(uintptr_t)ptr;
}

/**
 * Initialize the platform GL loader projectM was built against.
 * Windows: glewInit (required for core-profile contexts). Other hosts: no-op.
 * Must run with a current GL context, before the first projectm_create().
 */
JNIEXPORT jboolean JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeInitGlLoader(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#if defined(_WIN32)
    glewExperimental = GL_TRUE;
    GLenum err = glewInit();
    /* Known GLEW quirk on core profiles: glewInit can raise GL_INVALID_ENUM. */
    while (glGetError() != GL_NO_ERROR) {
    }
    return err == GLEW_OK ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeCreate(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    projectm_handle handle = projectm_create();
    return (jlong)(uintptr_t)handle;
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeDestroy(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle != NULL) {
        projectm_destroy(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeSetWindowSize(
    JNIEnv *env, jclass clazz, jlong ptr, jint width, jint height) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL || width <= 0 || height <= 0) {
        return;
    }
    projectm_set_window_size(handle, (size_t)width, (size_t)height);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeSetFps(
    JNIEnv *env, jclass clazz, jlong ptr, jint fps) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL) {
        return;
    }
    projectm_set_fps(handle, fps);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeRenderFrame(
    JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL) {
        return;
    }
    projectm_opengl_render_frame(handle);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeLoadPresetFile(
    JNIEnv *env, jclass clazz, jlong ptr, jstring path, jboolean smooth) {
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL || path == NULL) {
        return;
    }
    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cpath == NULL) {
        return;
    }
    projectm_load_preset_file(handle, cpath, smooth == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, path, cpath);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeLoadPresetData(
    JNIEnv *env, jclass clazz, jlong ptr, jstring data, jboolean smooth) {
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL || data == NULL) {
        return;
    }
    const char *cdata = (*env)->GetStringUTFChars(env, data, NULL);
    if (cdata == NULL) {
        return;
    }
    projectm_load_preset_data(handle, cdata, smooth == JNI_TRUE);
    (*env)->ReleaseStringUTFChars(env, data, cdata);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeAddPcmFloat(
    JNIEnv *env, jclass clazz, jlong ptr, jfloatArray samples, jint channels) {
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL || samples == NULL) {
        return;
    }
    jsize length = (*env)->GetArrayLength(env, samples);
    if (length <= 0) {
        return;
    }
    jfloat *body = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (body == NULL) {
        return;
    }
    projectm_channels ch = (channels >= 2) ? PROJECTM_STEREO : PROJECTM_MONO;
    unsigned int count = (ch == PROJECTM_STEREO)
        ? (unsigned int)(length / 2)
        : (unsigned int)length;
    if (count > 0) {
        projectm_pcm_add_float(handle, body, count, ch);
    }
    (*env)->ReleaseFloatArrayElements(env, samples, body, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeSetTextureSearchPaths(
    JNIEnv *env, jclass clazz, jlong ptr, jobjectArray paths) {
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL) {
        return;
    }
    if (paths == NULL) {
        projectm_set_texture_search_paths(handle, NULL, 0);
        return;
    }
    jsize count = (*env)->GetArrayLength(env, paths);
    if (count <= 0) {
        projectm_set_texture_search_paths(handle, NULL, 0);
        return;
    }
    const char **cpaths = (const char **)calloc((size_t)count, sizeof(char *));
    if (cpaths == NULL) {
        return;
    }
    for (jsize i = 0; i < count; i++) {
        jstring path = (jstring)(*env)->GetObjectArrayElement(env, paths, i);
        if (path != NULL) {
            cpaths[i] = (*env)->GetStringUTFChars(env, path, NULL);
        }
    }
    projectm_set_texture_search_paths(handle, cpaths, (size_t)count);
    for (jsize i = 0; i < count; i++) {
        jstring path = (jstring)(*env)->GetObjectArrayElement(env, paths, i);
        if (path != NULL && cpaths[i] != NULL) {
            (*env)->ReleaseStringUTFChars(env, path, cpaths[i]);
        }
        if (path != NULL) {
            (*env)->DeleteLocalRef(env, path);
        }
    }
    free(cpaths);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeSetPresetDuration(
    JNIEnv *env, jclass clazz, jlong ptr, jdouble seconds) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL) {
        return;
    }
    projectm_set_preset_duration(handle, seconds);
}

JNIEXPORT void JNICALL
Java_com_phoebe_app_feature_playback_ProjectMNative_nativeSetPresetLocked(
    JNIEnv *env, jclass clazz, jlong ptr, jboolean locked) {
    (void)env;
    (void)clazz;
    projectm_handle handle = handle_from(ptr);
    if (handle == NULL) {
        return;
    }
    projectm_set_preset_locked(handle, locked == JNI_TRUE);
}
