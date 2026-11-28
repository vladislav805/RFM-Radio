#include <jni.h>
#include <opus.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define OPUS_SAMPLE_RATE 48000
#define OPUS_CHANNELS 2
#define MAX_FRAME_SAMPLES 5760

typedef struct {
    int64_t offset;
    int64_t granule;
} OggPageEntry;

typedef struct {
    FILE *fp;
    OpusDecoder *decoder;
    int64_t pre_skip;
    int64_t total_samples;
    int64_t current_sample;
    int64_t skip_remaining;

    OggPageEntry *pages;
    size_t page_count;
    size_t page_cap;

    uint8_t page_lacing[255];
    int page_seg_count;
    int page_seg_idx;
    uint8_t *page_payload;
    int page_payload_len;
    int page_payload_pos;
    int page_loaded;

    uint8_t *packet_buf;
    size_t packet_cap;

    int16_t pcm_buf[MAX_FRAME_SAMPLES * OPUS_CHANNELS];
    int pcm_frames;
    int pcm_pos;
    int eof;
} OggOpusDecoderCtx;

static void throw_io_exception(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) {
        (*env)->ThrowNew(env, cls, message);
    }
}

static uint16_t read_u16_le(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static int64_t read_i64_le(const uint8_t *p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        v |= ((uint64_t)p[i]) << (i * 8);
    }
    return (int64_t)v;
}

static int read_ogg_page_header(
        FILE *fp,
        int64_t *out_offset,
        uint8_t *out_header_type,
        int64_t *out_granule,
        uint8_t out_lacing[255],
        int *out_seg_count,
        int *out_payload_len
) {
    int64_t off = (int64_t)ftello(fp);
    uint8_t hdr[27];
    if (fread(hdr, 1, 27, fp) != 27) {
        return 0;
    }

    if (memcmp(hdr, "OggS", 4) != 0) {
        return -1;
    }

    int seg_count = hdr[26];
    if (seg_count > 0 && fread(out_lacing, 1, (size_t)seg_count, fp) != (size_t)seg_count) {
        return -1;
    }

    int payload_len = 0;
    for (int i = 0; i < seg_count; i++) {
        payload_len += out_lacing[i];
    }

    *out_offset = off;
    *out_header_type = hdr[5];
    *out_granule = read_i64_le(hdr + 6);
    *out_seg_count = seg_count;
    *out_payload_len = payload_len;

    return 1;
}

static int push_page_entry(OggOpusDecoderCtx *ctx, int64_t offset, int64_t granule) {
    if (ctx->page_count >= ctx->page_cap) {
        size_t new_cap = ctx->page_cap == 0 ? 64 : ctx->page_cap * 2;
        OggPageEntry *grown = (OggPageEntry *)realloc(ctx->pages, new_cap * sizeof(OggPageEntry));

        if (grown == NULL) {
            return -1;
        }

        ctx->pages = grown;
        ctx->page_cap = new_cap;
    }

    ctx->pages[ctx->page_count].offset = offset;
    ctx->pages[ctx->page_count].granule = granule;
    ctx->page_count++;

    return 0;
}

static int load_next_page(OggOpusDecoderCtx *ctx) {
    int64_t off = 0;
    uint8_t header_type = 0;
    int64_t granule = 0;
    int seg_count = 0;
    int payload_len = 0;

    int res = read_ogg_page_header(ctx->fp, &off, &header_type, &granule, ctx->page_lacing, &seg_count, &payload_len);
    if (res <= 0) {
        ctx->eof = 1;
        ctx->page_loaded = 0;
        return res;
    }

    uint8_t *buf = NULL;
    if (payload_len > 0) {
        buf = (uint8_t *)realloc(ctx->page_payload, (size_t)payload_len);
        if (buf == NULL) {
            return -1;
        }

        ctx->page_payload = buf;

        if (fread(ctx->page_payload, 1, (size_t)payload_len, ctx->fp) != (size_t)payload_len) {
            ctx->eof = 1;
            return -1;
        }
    }

    ctx->page_seg_count = seg_count;
    ctx->page_seg_idx = 0;
    ctx->page_payload_len = payload_len;
    ctx->page_payload_pos = 0;
    ctx->page_loaded = 1;

    return 1;
}

static int read_next_packet(OggOpusDecoderCtx *ctx, size_t *out_len) {
    size_t pkt_len = 0;

    while (1) {
        if (!ctx->page_loaded || ctx->page_seg_idx >= ctx->page_seg_count) {
            int r = load_next_page(ctx);

            if (r <= 0) {
                if (pkt_len > 0) {
                    *out_len = pkt_len;
                    return 1;
                }
                return r;
            }
        }

        int seg_len = ctx->page_lacing[ctx->page_seg_idx++];
        if (ctx->page_payload_pos + seg_len > ctx->page_payload_len) {
            return -1;
        }

        if (pkt_len + (size_t)seg_len > ctx->packet_cap) {
            size_t new_cap = (pkt_len + (size_t)seg_len) * 2;

            if (new_cap < 4096) {
                new_cap = 4096;
            }

            uint8_t *grown = (uint8_t *)realloc(ctx->packet_buf, new_cap);

            if (grown == NULL) {
                return -1;
            }

            ctx->packet_buf = grown;
            ctx->packet_cap = new_cap;
        }

        if (seg_len > 0) {
            memcpy(ctx->packet_buf + pkt_len, ctx->page_payload + ctx->page_payload_pos, (size_t)seg_len);
            ctx->page_payload_pos += seg_len;
            pkt_len += (size_t)seg_len;
        }

        if (seg_len < 255) {
            *out_len = pkt_len;
            return 1;
        }
    }
}

static void destroy_ctx(OggOpusDecoderCtx *ctx) {
    if (ctx == NULL) {
        return;
    }

    if (ctx->decoder != NULL) {
        opus_decoder_destroy(ctx->decoder);
    }

    if (ctx->fp != NULL) {
        fclose(ctx->fp);
    }

    free(ctx->pages);
    free(ctx->page_payload);
    free(ctx->packet_buf);
    free(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_vlad805_fmradio_recordings_OggOpusDecoder_nativeOpenFd(
        JNIEnv *env,
        jclass cls,
        jint fd
) {
    (void)cls;
    int duped = dup(fd);
    if (duped < 0) {
        throw_io_exception(env, "Cannot duplicate file descriptor");
        return 0;
    }

    FILE *fp = fdopen(duped, "rb");
    if (fp == NULL) {
        close(duped);
        throw_io_exception(env, "Cannot open file stream");
        return 0;
    }
    fseeko(fp, 0, SEEK_SET);

    OggOpusDecoderCtx *ctx = (OggOpusDecoderCtx *)calloc(1, sizeof(OggOpusDecoderCtx));
    if (ctx == NULL) {
        fclose(fp);
        throw_io_exception(env, "Out of memory");
        return 0;
    }
    ctx->fp = fp;

    int64_t off = 0;
    uint8_t htype = 0;
    int64_t granule = 0;
    uint8_t lacing[255];
    int seg_count = 0;
    int payload_len = 0;

    /* Page 0: OpusHead */
    if (read_ogg_page_header(fp, &off, &htype, &granule, lacing, &seg_count, &payload_len) != 1 || payload_len < 19) {
        destroy_ctx(ctx);
        throw_io_exception(env, "Invalid Ogg Opus header page");
        return 0;
    }

    uint8_t head[19];
    if (fread(head, 1, 19, fp) != 19 || memcmp(head, "OpusHead", 8) != 0) {
        destroy_ctx(ctx);
        throw_io_exception(env, "Not an Ogg Opus stream");
        return 0;
    }

    ctx->pre_skip = (int64_t)read_u16_le(head + 10);
    if (payload_len > 19) {
        fseeko(fp, payload_len - 19, SEEK_CUR);
    }

    /* Page 1..K: OpusTags (may span multiple pages if last lacing == 255) */
    int tags_continue = 1;
    int first_tag = 1;

    while (tags_continue) {
        int r = read_ogg_page_header(fp, &off, &htype, &granule, lacing, &seg_count, &payload_len);

        if (r != 1) {
            destroy_ctx(ctx);
            throw_io_exception(env, "Missing OpusTags page");
            return 0;
        }

        if (!first_tag && (htype & 0x01) == 0) {
            /* Overshot into first audio page */
            fseeko(fp, off, SEEK_SET);
            break;
        }

        first_tag = 0;

        if (payload_len > 0) {
            fseeko(fp, payload_len, SEEK_CUR);
        }

        tags_continue = (seg_count > 0 && lacing[seg_count - 1] == 255);
    }

    /* Index all audio pages */
    int64_t last_granule = 0;
    while (1) {
        int r = read_ogg_page_header(fp, &off, &htype, &granule, lacing, &seg_count, &payload_len);
        if (r == 0) {
            break;
        }

        if (r < 0) {
            break;
        }

        if (push_page_entry(ctx, off, granule) != 0) {
            destroy_ctx(ctx);
            throw_io_exception(env, "Out of memory indexing Ogg pages");
            return 0;
        }

        if (granule > last_granule) {
            last_granule = granule;
        }

        if (payload_len > 0) {
            fseeko(fp, payload_len, SEEK_CUR);
        }
    }

    ctx->total_samples = last_granule > ctx->pre_skip ? (last_granule - ctx->pre_skip) : 0;

    int opus_err = OPUS_OK;
    ctx->decoder = opus_decoder_create(OPUS_SAMPLE_RATE, OPUS_CHANNELS, &opus_err);

    if (ctx->decoder == NULL || opus_err != OPUS_OK) {
        destroy_ctx(ctx);
        throw_io_exception(env, "Failed to create Opus decoder");
        return 0;
    }

    if (ctx->page_count > 0) {
        fseeko(fp, ctx->pages[0].offset, SEEK_SET);
        ctx->skip_remaining = ctx->pre_skip;
    } else {
        ctx->eof = 1;
    }

    return (jlong)(intptr_t)ctx;
}

JNIEXPORT jlong JNICALL
Java_com_vlad805_fmradio_recordings_OggOpusDecoder_nativeGetTotalSamples(
        JNIEnv *env,
        jclass cls,
        jlong handle
) {
    (void)env;
    (void)cls;

    OggOpusDecoderCtx *ctx = (OggOpusDecoderCtx *)(intptr_t)handle;
    return ctx != NULL ? (jlong)ctx->total_samples : 0;
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_recordings_OggOpusDecoder_nativeSeek(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jlong target_sample
) {
    (void)env;
    (void)cls;

    OggOpusDecoderCtx *ctx = (OggOpusDecoderCtx *)(intptr_t)handle;
    if (ctx == NULL || ctx->page_count == 0) {
        return;
    }

    int64_t clamped = target_sample;
    if (clamped < 0) clamped = 0;
    if (clamped > ctx->total_samples) clamped = ctx->total_samples;

    int64_t target_raw = clamped + ctx->pre_skip;

    /* Find first page whose granule >= target_raw, then step back 1 page for decoder convergence. */
    size_t low = 0;
    size_t high = ctx->page_count - 1;
    while (low < high) {
        size_t mid = low + (high - low) / 2;
        if (ctx->pages[mid].granule < target_raw) {
            low = mid + 1;
        } else {
            high = mid;
        }
    }

    size_t start_page = low > 0 ? low - 1 : 0;
    int64_t start_granule = start_page == 0 ? 0 : ctx->pages[start_page - 1].granule;
    if (start_granule < 0) {
        start_granule = 0;
    }

    fseeko(ctx->fp, ctx->pages[start_page].offset, SEEK_SET);
    opus_decoder_ctl(ctx->decoder, OPUS_RESET_STATE);

    ctx->page_loaded = 0;
    ctx->page_seg_idx = 0;
    ctx->page_seg_count = 0;
    ctx->pcm_frames = 0;
    ctx->pcm_pos = 0;
    ctx->eof = (clamped >= ctx->total_samples) ? 1 : 0;
    ctx->current_sample = clamped;
    ctx->skip_remaining = target_raw > start_granule ? (target_raw - start_granule) : 0;
}

JNIEXPORT jint JNICALL
Java_com_vlad805_fmradio_recordings_OggOpusDecoder_nativeDecode(
        JNIEnv *env,
        jclass cls,
        jlong handle,
        jshortArray out_pcm
) {
    (void)cls;
    OggOpusDecoderCtx *ctx = (OggOpusDecoderCtx *)(intptr_t)handle;
    if (ctx == NULL || ctx->eof) {
        return 0;
    }

    jsize out_cap_shorts = (*env)->GetArrayLength(env, out_pcm);
    int out_cap_frames = out_cap_shorts / OPUS_CHANNELS;
    if (out_cap_frames <= 0) {
        return 0;
    }

    jshort *dst = (*env)->GetShortArrayElements(env, out_pcm, NULL);
    if (dst == NULL) {
        return -1;
    }

    int written_frames = 0;
    while (written_frames < out_cap_frames && !ctx->eof) {
        if (ctx->current_sample >= ctx->total_samples) {
            ctx->eof = 1;
            break;
        }

        if (ctx->pcm_pos < ctx->pcm_frames) {
            int avail = ctx->pcm_frames - ctx->pcm_pos;

            if (ctx->skip_remaining > 0) {
                int to_skip = (int)(ctx->skip_remaining < avail ? ctx->skip_remaining : avail);
                ctx->pcm_pos += to_skip;
                ctx->skip_remaining -= to_skip;
                continue;
            }

            int64_t remaining_total = ctx->total_samples - ctx->current_sample;
            int need = out_cap_frames - written_frames;
            int take = avail < need ? avail : need;
            if ((int64_t)take > remaining_total) {
                take = (int)remaining_total;
            }

            memcpy(
                    dst + (written_frames * OPUS_CHANNELS),
                    ctx->pcm_buf + (ctx->pcm_pos * OPUS_CHANNELS),
                    (size_t)take * OPUS_CHANNELS * sizeof(int16_t)
            );
            ctx->pcm_pos += take;
            written_frames += take;
            ctx->current_sample += take;
            continue;
        }

        size_t pkt_len = 0;
        int r = read_next_packet(ctx, &pkt_len);
        if (r <= 0) {
            ctx->eof = 1;
            break;
        }
        if (pkt_len == 0) {
            continue;
        }

        int decoded = opus_decode(
                ctx->decoder,
                ctx->packet_buf,
                (opus_int32)pkt_len,
                ctx->pcm_buf,
                MAX_FRAME_SAMPLES,
                0
        );
        if (decoded < 0) {
            ctx->eof = 1;
            break;
        }
        ctx->pcm_frames = decoded;
        ctx->pcm_pos = 0;
    }

    (*env)->ReleaseShortArrayElements(env, out_pcm, dst, 0);
    return written_frames * OPUS_CHANNELS;
}

JNIEXPORT void JNICALL
Java_com_vlad805_fmradio_recordings_OggOpusDecoder_nativeClose(
        JNIEnv *env,
        jclass cls,
        jlong handle
) {
    (void)env;
    (void)cls;
    destroy_ctx((OggOpusDecoderCtx *)(intptr_t)handle);
}
