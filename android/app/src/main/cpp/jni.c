#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "pocket.h"
#include "speaker.h"

#define FUNCTION(name) Java_dev_tn3w_shelf_data_SpeakerModel_##name

static Speaker *speaker_of(jlong handle) {
    return (Speaker *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL FUNCTION(load)(JNIEnv *env, jclass type, jstring path, jint context,
                                       jint threads) {
    (void)type;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (!chars) return 0;
    Speaker *speaker = speaker_open(chars, context, threads);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return (jlong)(intptr_t)speaker;
}

JNIEXPORT void JNICALL FUNCTION(release)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    speaker_close(speaker_of(handle));
}

JNIEXPORT jboolean JNICALL FUNCTION(feed)(JNIEnv *env, jclass type, jlong handle,
                                          jintArray tokens, jint position) {
    (void)type;
    jsize count = (*env)->GetArrayLength(env, tokens);
    jint *values = (*env)->GetIntArrayElements(env, tokens, NULL);
    if (!values) return JNI_FALSE;
    int done = speaker_evaluate(speaker_of(handle), (const int32_t *)values, count, position);
    (*env)->ReleaseIntArrayElements(env, tokens, values, JNI_ABORT);
    return done ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL FUNCTION(score)(JNIEnv *env, jclass type, jlong handle,
                                              jintArray allowed) {
    (void)type;
    Speaker *speaker = speaker_of(handle);
    jsize count = (*env)->GetArrayLength(env, allowed);
    jint *ids = (*env)->GetIntArrayElements(env, allowed, NULL);
    if (!ids) return NULL;
    const float *logits = speaker_logits(speaker, (const int32_t *)ids, count);
    jfloatArray result = (*env)->NewFloatArray(env, count);
    float *scores = malloc((size_t)(count > 0 ? count : 1) * sizeof(float));
    for (jsize index = 0; result && scores && index < count; index++) {
        scores[index] = logits[ids[index]];
    }
    if (result && scores) (*env)->SetFloatArrayRegion(env, result, 0, count, scores);
    free(scores);
    (*env)->ReleaseIntArrayElements(env, allowed, ids, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL FUNCTION(next)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    Speaker *speaker = speaker_of(handle);
    const float *logits = speaker_logits(speaker, NULL, 0);
    int best = 0;
    for (int index = 1; index < speaker_vocabulary(speaker); index++) {
        if (logits[index] > logits[best]) best = index;
    }
    return best;
}

#define POCKET(name) Java_dev_tn3w_shelf_data_PocketModel_##name

static Pocket *pocket_of(jlong handle) {
    return (Pocket *)(intptr_t)handle;
}

JNIEXPORT jlong JNICALL POCKET(load)(JNIEnv *env, jclass type, jstring path, jint threads) {
    (void)type;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (!chars) return 0;
    Pocket *pocket = pocket_open(chars, threads);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return (jlong)(intptr_t)pocket;
}

JNIEXPORT void JNICALL POCKET(release)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    pocket_close(pocket_of(handle));
}

JNIEXPORT jboolean JNICALL POCKET(start)(JNIEnv *env, jclass type, jlong handle, jstring voice,
                                          jintArray tokens, jint after, jfloat temperature,
                                          jlong seed) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, voice, NULL);
    if (!name) return JNI_FALSE;
    jsize count = (*env)->GetArrayLength(env, tokens);
    jint *values = (*env)->GetIntArrayElements(env, tokens, NULL);
    int started = values && pocket_start(pocket_of(handle), name, (const int32_t *)values, count,
                                         after, temperature, (uint64_t)seed);
    if (values) (*env)->ReleaseIntArrayElements(env, tokens, values, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, voice, name);
    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL POCKET(next)(JNIEnv *env, jclass type, jlong handle, jfloatArray out) {
    (void)type;
    float samples[POCKET_FRAME];
    int count = pocket_next(pocket_of(handle), samples);
    if (count > 0) (*env)->SetFloatArrayRegion(env, out, 0, count, samples);
    return count;
}
