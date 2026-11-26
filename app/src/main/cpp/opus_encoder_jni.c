#include <jni.h>
#include <stdint.h>
#include <opusenc.h>

static void throw_io_error(JNIEnv *env, int error) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, ope_strerror(error));
    }
}

JNIEXPORT jlong JNICALL
Java_com_vlad805_fmradio_service_recording_OggOpusEncoder_nativeCreate(
        JNIEnv *env,
        jclass type,
        jint sample_rate,
        jint bitrate
) {
    OggOpusComments *comments = ope_comments_create();
    if (comments == NULL) {
        throw_io_error(env, OPE_ALLOC_FAIL);
        return 0;
    }

    int error;
    OggOpusEnc *encoder = ope_encoder_create_pull(comments, sample_rate, 2, 0, &error);
    ope_comments_destroy(comments);
    if (encoder == NULL) {
        throw_io_error(env, error);
        return 0;
    }

    error = ope_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    if (error != OPE_OK) {
        ope_encoder_destroy(encoder);
        throw_io_error(env, error);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_service_recording_OggOpusEncoder_nativeWrite(
        JNIEnv *env,
        jclass type,
        jlong handle,
        jshortArray pcm,
        jint length
) {
    if (length < 0 || length % 2 != 0 || length > (*env)->GetArrayLength(env, pcm)) {
        throw_io_error(env, OPE_BAD_ARG);
        return;
    }
    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (samples == NULL) {
        return;
    }
    int error = ope_encoder_write((OggOpusEnc *)(intptr_t)handle, samples, length / 2);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (error != OPE_OK) {
        throw_io_error(env, error);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_vlad805_fmradio_service_recording_OggOpusEncoder_nativeNextPage(
        JNIEnv *env,
        jclass type,
        jlong handle
) {
    unsigned char *page;
    opus_int32 size;
    int result = ope_encoder_get_page((OggOpusEnc *)(intptr_t)handle, &page, &size, 0);
    if (result < 0) {
        throw_io_error(env, result);
    }
    if (result <= 0) {
        return NULL;
    }
    jbyteArray bytes = (*env)->NewByteArray(env, size);
    if (bytes != NULL) {
        (*env)->SetByteArrayRegion(env, bytes, 0, size, (const jbyte *)page);
    }
    return bytes;
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_service_recording_OggOpusEncoder_nativeDrain(
        JNIEnv *env,
        jclass type,
        jlong handle
) {
    int error = ope_encoder_drain((OggOpusEnc *)(intptr_t)handle);
    if (error != OPE_OK) {
        throw_io_error(env, error);
    }
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_service_recording_OggOpusEncoder_nativeDestroy(
        JNIEnv *env,
        jclass type,
        jlong handle
) {
    ope_encoder_destroy((OggOpusEnc *)(intptr_t)handle);
}
