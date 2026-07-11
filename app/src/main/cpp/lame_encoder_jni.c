#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "lame.h"

static lame_global_flags *from_handle(jlong handle) {
    return (lame_global_flags *)(intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_com_vlad805_fmradio_service_recording_LameMp3Encoder_nativeInit(
        JNIEnv *env,
        jclass clazz,
        jint sample_rate,
        jint channels,
        jint bitrate,
        jint quality
) {
    (void) env;
    (void) clazz;

    lame_global_flags *lame = lame_init();
    if (lame == NULL) {
        return 0;
    }

    lame_set_in_samplerate(lame, sample_rate);
    lame_set_out_samplerate(lame, sample_rate);
    lame_set_num_channels(lame, channels);
    lame_set_brate(lame, bitrate);
    lame_set_mode(lame, JOINT_STEREO);
    lame_set_quality(lame, quality);

    if (lame_init_params(lame) < 0) {
        lame_close(lame);
        return 0;
    }

    return (jlong)(intptr_t) lame;
}

JNIEXPORT jint JNICALL
Java_com_vlad805_fmradio_service_recording_LameMp3Encoder_nativeEncodeInterleaved(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jshortArray pcm,
        jint samples_per_channel,
        jbyteArray output
) {
    (void) clazz;

    lame_global_flags *lame = from_handle(handle);
    if (lame == NULL || pcm == NULL || output == NULL) {
        return -1;
    }

    jshort *pcm_ptr = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *out_ptr = (*env)->GetByteArrayElements(env, output, NULL);
    if (pcm_ptr == NULL || out_ptr == NULL) {
        if (pcm_ptr != NULL) {
            (*env)->ReleaseShortArrayElements(env, pcm, pcm_ptr, JNI_ABORT);
        }
        if (out_ptr != NULL) {
            (*env)->ReleaseByteArrayElements(env, output, out_ptr, 0);
        }
        return -1;
    }

    const jint output_size = (*env)->GetArrayLength(env, output);
    const int written = lame_encode_buffer_interleaved(
            lame,
            pcm_ptr,
            samples_per_channel,
            (unsigned char *) out_ptr,
            output_size
    );

    (*env)->ReleaseShortArrayElements(env, pcm, pcm_ptr, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, output, out_ptr, 0);

    return written;
}

JNIEXPORT jint JNICALL
Java_com_vlad805_fmradio_service_recording_LameMp3Encoder_nativeFlush(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jbyteArray output
) {
    (void) clazz;

    lame_global_flags *lame = from_handle(handle);
    if (lame == NULL || output == NULL) {
        return -1;
    }

    jbyte *out_ptr = (*env)->GetByteArrayElements(env, output, NULL);
    if (out_ptr == NULL) {
        return -1;
    }

    const jint output_size = (*env)->GetArrayLength(env, output);
    const int written = lame_encode_flush(lame, (unsigned char *) out_ptr, output_size);
    (*env)->ReleaseByteArrayElements(env, output, out_ptr, 0);

    return written;
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_service_recording_LameMp3Encoder_nativeClose(
        JNIEnv *env,
        jclass clazz,
        jlong handle
) {
    (void) env;
    (void) clazz;

    lame_global_flags *lame = from_handle(handle);
    if (lame != NULL) {
        lame_close(lame);
    }
}
