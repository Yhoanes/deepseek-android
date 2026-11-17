#include <jni.h>
#include <stddef.h>

const char* ds_jni_get_utf(JNIEnv* env, jstring s) {
    if (!env || !s) {
        return NULL;
    }
    return (*env)->GetStringUTFChars(env, s, NULL);
}

void ds_jni_release_utf(JNIEnv* env, jstring s, const char* utf) {
    if (env && s && utf) {
        (*env)->ReleaseStringUTFChars(env, s, utf);
    }
}

jstring ds_jni_new_utf(JNIEnv* env, const char* utf) {
    if (!env || !utf) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, utf);
}
