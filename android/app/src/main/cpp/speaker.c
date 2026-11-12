#define PASTE(prefix, name) prefix##name
#define VARIANT_NAME(prefix, name) PASTE(prefix, name)
#define speaker_open VARIANT_NAME(VARIANT, speaker_open)
#define speaker_close VARIANT_NAME(VARIANT, speaker_close)
#define speaker_vocabulary VARIANT_NAME(VARIANT, speaker_vocabulary)
#define speaker_evaluate VARIANT_NAME(VARIANT, speaker_evaluate)
#define speaker_logits VARIANT_NAME(VARIANT, speaker_logits)

#include "speaker.h"
#include "pool.h"

#include <fcntl.h>
#include <math.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

enum { BLOCK = 256, BATCH = 32, HEAD = 128, MAX_TENSORS = 512 };
enum { CHUNK = 16 };
enum { TYPE_F32 = 0, TYPE_Q4_K = 12, TYPE_Q6_K = 14 };
enum { Q4_K_BYTES = 144, Q6_K_BYTES = 210 };

typedef struct {
    float scale;
    int16_t sums[16];
    int8_t values[BLOCK];
} Quantized;

typedef struct {
    char name[64];
    int type;
    int64_t columns;
    int64_t rows;
    const uint8_t *data;
    void *packed;
} Tensor;

typedef struct {
    uint8_t nibbles[512];
    int16_t scales[8][4];
    int16_t mins[8][4];
    float scale[4];
    float min_scale[4];
} PackedQ4;

typedef struct {
    int8_t values[1024];
    int16_t scales[16][4];
    float scale[4];
} PackedQ6;

typedef struct {
    const Tensor *attention_norm, *query, *key, *value, *output;
    const Tensor *query_norm, *key_norm, *ffn_norm, *gate, *up, *down;
} Layer;

struct Speaker {
    uint8_t *file;
    size_t file_size;
    Tensor tensors[MAX_TENSORS];
    int tensor_count;
    Layer *layers;
    const Tensor *embedding, *output_norm;
    int layer_count, width, hidden, heads, kv_heads, vocabulary, context;
    float rope_base, epsilon;
    _Float16 *keys, *values;
    float *x, *normed, *query, *key, *value, *attended, *gate, *up, *down, *scores;
    float *last, *logits, *rope;
    Quantized *quantized;
    Pool pool;
};

typedef struct {
    const uint8_t *at;
} Reader;

static uint32_t read_u32(Reader *reader) {
    uint32_t value;
    memcpy(&value, reader->at, 4);
    reader->at += 4;
    return value;
}

static uint64_t read_u64(Reader *reader) {
    uint64_t value;
    memcpy(&value, reader->at, 8);
    reader->at += 8;
    return value;
}

static float read_f32(Reader *reader) {
    float value;
    memcpy(&value, reader->at, 4);
    reader->at += 4;
    return value;
}

static void read_string(Reader *reader, const char **text, uint64_t *length) {
    *length = read_u64(reader);
    *text = (const char *)reader->at;
    reader->at += *length;
}

static void skip_value(Reader *reader, uint32_t type) {
    static const int sizes[] = {1, 1, 2, 2, 4, 4, 4, 1, 0, 0, 8, 8, 8};
    if (type == 8) {
        reader->at += read_u64(reader);
        return;
    }
    if (type != 9) {
        reader->at += sizes[type];
        return;
    }
    uint32_t item_type = read_u32(reader);
    uint64_t count = read_u64(reader);
    for (uint64_t index = 0; index < count; index++) skip_value(reader, item_type);
}

static int ends_with(const char *key, uint64_t length, const char *suffix) {
    size_t suffix_length = strlen(suffix);
    return length >= suffix_length &&
           memcmp(key + length - suffix_length, suffix, suffix_length) == 0;
}

static void read_setting(Speaker *speaker, Reader *reader, const char *key, uint64_t length,
                         uint32_t type, uint32_t *alignment) {
    if (type == 6) {
        float value = read_f32(reader);
        if (ends_with(key, length, ".rope.freq_base")) speaker->rope_base = value;
        if (ends_with(key, length, "layer_norm_rms_epsilon")) speaker->epsilon = value;
        return;
    }
    if (type != 4) {
        skip_value(reader, type);
        return;
    }
    uint32_t value = read_u32(reader);
    if (ends_with(key, length, ".block_count")) speaker->layer_count = value;
    if (ends_with(key, length, ".embedding_length")) speaker->width = value;
    if (ends_with(key, length, ".feed_forward_length")) speaker->hidden = value;
    if (ends_with(key, length, ".attention.head_count")) speaker->heads = value;
    if (ends_with(key, length, ".attention.head_count_kv")) speaker->kv_heads = value;
    if (ends_with(key, length, "general.alignment")) *alignment = value;
}

static int64_t row_bytes(const Tensor *tensor) {
    if (tensor->type == TYPE_Q4_K) return tensor->columns / BLOCK * Q4_K_BYTES;
    if (tensor->type == TYPE_Q6_K) return tensor->columns / BLOCK * Q6_K_BYTES;
    return tensor->columns * 4;
}

static int parse(Speaker *speaker) {
    Reader reader = {speaker->file};
    if (memcmp(speaker->file, "GGUF", 4) != 0) return 0;
    reader.at += 8;
    uint64_t tensor_count = read_u64(&reader);
    uint64_t setting_count = read_u64(&reader);
    if (tensor_count > MAX_TENSORS) return 0;
    uint32_t alignment = 32;
    for (uint64_t index = 0; index < setting_count; index++) {
        const char *key;
        uint64_t length;
        read_string(&reader, &key, &length);
        read_setting(speaker, &reader, key, length, read_u32(&reader), &alignment);
    }
    uint64_t offsets[MAX_TENSORS];
    for (uint64_t index = 0; index < tensor_count; index++) {
        Tensor *tensor = &speaker->tensors[index];
        const char *name;
        uint64_t length;
        read_string(&reader, &name, &length);
        memcpy(tensor->name, name, length < 63 ? length : 63);
        uint32_t dimensions = read_u32(&reader);
        tensor->columns = (int64_t)read_u64(&reader);
        tensor->rows = 1;
        for (uint32_t dimension = 1; dimension < dimensions; dimension++) {
            tensor->rows *= (int64_t)read_u64(&reader);
        }
        tensor->type = (int)read_u32(&reader);
        offsets[index] = read_u64(&reader);
    }
    size_t header = (size_t)(reader.at - speaker->file);
    size_t data = (header + alignment - 1) / alignment * alignment;
    for (uint64_t index = 0; index < tensor_count; index++) {
        speaker->tensors[index].data = speaker->file + data + offsets[index];
    }
    speaker->tensor_count = (int)tensor_count;
    return 1;
}

static const Tensor *find(const Speaker *speaker, const char *name, int layer) {
    char full[64];
    if (layer < 0) {
        strncpy(full, name, sizeof full - 1);
        full[sizeof full - 1] = 0;
    } else {
        snprintf(full, sizeof full, "blk.%d.%s.weight", layer, name);
    }
    for (int index = 0; index < speaker->tensor_count; index++) {
        if (strcmp(speaker->tensors[index].name, full) == 0) return &speaker->tensors[index];
    }
    return NULL;
}

static float fast_exp(float value) {
    if (value < -87.0f) return 0;
    if (value > 88.0f) value = 88.0f;
    float scaled = value * 1.44269504f;
    float whole = floorf(scaled);
    float part = (scaled - whole) * 0.69314718f;
    float polynomial =
        1 + part * (1 + part * (0.5f + part * (0.16666667f + part * (0.04166667f +
                                                                      part * 0.00833333f))));
    int32_t bits = (int32_t)(whole + 127) << 23;
    float power;
    memcpy(&power, &bits, 4);
    return polynomial * power;
}

static float half(const uint8_t *bytes) {
    _Float16 value;
    memcpy(&value, bytes, 2);
    return (float)value;
}

static void scale_and_min(int index, const uint8_t *packed, uint8_t *scale, uint8_t *min) {
    if (index < 4) {
        *scale = packed[index] & 63;
        *min = packed[index + 4] & 63;
        return;
    }
    *scale = (packed[index + 4] & 15) | ((packed[index - 4] >> 6) << 4);
    *min = (packed[index + 4] >> 4) | ((packed[index] >> 6) << 4);
}

static void unpack_q4_k(const uint8_t *block, int8_t *values, int *scales, int *mins) {
    for (int index = 0; index < 8; index++) {
        uint8_t scale, min;
        scale_and_min(index, block + 4, &scale, &min);
        scales[index] = scale;
        mins[index] = min;
    }
    const uint8_t *packed = block + 16;
    for (int chunk = 0; chunk < 4; chunk++) {
        int8_t *target = values + chunk * 64;
#if defined(__aarch64__)
        for (int offset = 0; offset < 32; offset += 16) {
            uint8x16_t bytes = vld1q_u8(packed + offset);
            vst1q_s8(target + offset, vreinterpretq_s8_u8(vandq_u8(bytes, vdupq_n_u8(15))));
            vst1q_s8(target + 32 + offset, vreinterpretq_s8_u8(vshrq_n_u8(bytes, 4)));
        }
#else
        for (int offset = 0; offset < 32; offset++) {
            target[offset] = (int8_t)(packed[offset] & 15);
            target[32 + offset] = (int8_t)(packed[offset] >> 4);
        }
#endif
        packed += 32;
    }
}

static void unpack_q6_k(const uint8_t *block, int8_t *values) {
    const uint8_t *low = block;
    const uint8_t *high = block + 128;
    for (int half_block = 0; half_block < 2; half_block++) {
        int8_t *target = values + half_block * 128;
#if defined(__aarch64__)
        uint8x16_t mask = vdupq_n_u8(15), two_bits = vdupq_n_u8(3);
        int8x16_t offset = vdupq_n_s8(32);
        for (int index = 0; index < 32; index += 16) {
            uint8x16_t first = vld1q_u8(low + index);
            uint8x16_t second = vld1q_u8(low + index + 32);
            uint8x16_t bits = vld1q_u8(high + index);
            uint8x16_t parts[4] = {
                vorrq_u8(vandq_u8(first, mask), vshlq_n_u8(vandq_u8(bits, two_bits), 4)),
                vorrq_u8(vandq_u8(second, mask),
                         vshlq_n_u8(vandq_u8(vshrq_n_u8(bits, 2), two_bits), 4)),
                vorrq_u8(vshrq_n_u8(first, 4),
                         vshlq_n_u8(vandq_u8(vshrq_n_u8(bits, 4), two_bits), 4)),
                vorrq_u8(vshrq_n_u8(second, 4), vshlq_n_u8(vshrq_n_u8(bits, 6), 4)),
            };
            for (int part = 0; part < 4; part++) {
                int8x16_t shifted = vsubq_s8(vreinterpretq_s8_u8(parts[part]), offset);
                vst1q_s8(target + part * 32 + index, shifted);
            }
        }
        low += 64;
        high += 32;
        continue;
#endif
        for (int index = 0; index < 32; index++) {
            uint8_t bits = high[index];
            target[index] = (int8_t)(((low[index] & 15) | ((bits & 3) << 4)) - 32);
            target[index + 32] =
                (int8_t)(((low[index + 32] & 15) | (((bits >> 2) & 3) << 4)) - 32);
            target[index + 64] = (int8_t)(((low[index] >> 4) | (((bits >> 4) & 3) << 4)) - 32);
            target[index + 96] =
                (int8_t)(((low[index + 32] >> 4) | (((bits >> 6) & 3) << 4)) - 32);
        }
        low += 64;
        high += 32;
    }
}

typedef struct {
    int8_t values[BLOCK * 4];
    int32_t scales[16][4];
    int32_t mins[8][4];
    float scale[4];
    float min_scale[4];
} Tile;

static void unpack_row(int type, const uint8_t *block, int lane, int8_t *values, Tile *tile) {
    if (type == TYPE_Q4_K) {
        int scales[8], mins[8];
        unpack_q4_k(block, values, scales, mins);
        for (int index = 0; index < 16; index++) tile->scales[index][lane] = scales[index / 2];
        for (int index = 0; index < 8; index++) tile->mins[index][lane] = mins[index];
        tile->scale[lane] = half(block);
        tile->min_scale[lane] = half(block + 2);
        return;
    }
    unpack_q6_k(block, values);
    const int8_t *scales = (const int8_t *)(block + 192);
    for (int index = 0; index < 16; index++) tile->scales[index][lane] = scales[index];
    for (int index = 0; index < 8; index++) tile->mins[index][lane] = 0;
    tile->scale[lane] = half(block + 208);
    tile->min_scale[lane] = 0;
}

static void unpack_tile(int type, const uint8_t *const *blocks, Tile *tile) {
    int8_t values[4][BLOCK];
    for (int lane = 0; lane < 4; lane++) unpack_row(type, blocks[lane], lane, values[lane], tile);
#if defined(__aarch64__)
    for (int start = 0; start < BLOCK; start += 16) {
        uint32x4_t first = vreinterpretq_u32_s8(vld1q_s8(values[0] + start));
        uint32x4_t second = vreinterpretq_u32_s8(vld1q_s8(values[1] + start));
        uint32x4_t third = vreinterpretq_u32_s8(vld1q_s8(values[2] + start));
        uint32x4_t fourth = vreinterpretq_u32_s8(vld1q_s8(values[3] + start));
        uint64x2_t even_low = vreinterpretq_u64_u32(vtrn1q_u32(first, second));
        uint64x2_t odd_low = vreinterpretq_u64_u32(vtrn2q_u32(first, second));
        uint64x2_t even_high = vreinterpretq_u64_u32(vtrn1q_u32(third, fourth));
        uint64x2_t odd_high = vreinterpretq_u64_u32(vtrn2q_u32(third, fourth));
        int8_t *target = tile->values + start * 4;
        vst1q_s8(target, vreinterpretq_s8_u64(vtrn1q_u64(even_low, even_high)));
        vst1q_s8(target + 16, vreinterpretq_s8_u64(vtrn1q_u64(odd_low, odd_high)));
        vst1q_s8(target + 32, vreinterpretq_s8_u64(vtrn2q_u64(even_low, even_high)));
        vst1q_s8(target + 48, vreinterpretq_s8_u64(vtrn2q_u64(odd_low, odd_high)));
    }
    return;
#endif
    for (int group = 0; group < BLOCK / 4; group++) {
        for (int lane = 0; lane < 4; lane++) {
            memcpy(tile->values + (group * 4 + lane) * 4, values[lane] + group * 4, 4);
        }
    }
}

#if defined(__ARM_FEATURE_DOTPROD)
#define DOT_LANE(sum, weights, input, lane) vdotq_laneq_s32(sum, weights, input, lane)
#elif defined(__aarch64__)
#define BROADCAST(input, lane) \
    vreinterpretq_s8_s32(vdupq_laneq_s32(vreinterpretq_s32_s8(input), lane))
#define DOT_LANE(sum, weights, input, lane) dot_lane(sum, weights, BROADCAST(input, lane))

static inline int32x4_t dot_lane(int32x4_t sum, int8x16_t weights, int8x16_t input) {
    int16x8_t low = vmull_s8(vget_low_s8(weights), vget_low_s8(input));
    int16x8_t high = vmull_high_s8(weights, input);
    return vpadalq_s16(sum, vpaddq_s16(low, high));
}
#endif

#if defined(__aarch64__)
static void tile_products(const Tile *tile, const Quantized *input, float *sums) {
    int32x4_t even = vdupq_n_s32(0), odd = vdupq_n_s32(0);
    for (int group = 0; group < 16; group += 2) {
        const int8_t *weights = tile->values + group * 64;
        int8x16_t first = vld1q_s8(input->values + group * 16);
        int8x16_t second = vld1q_s8(input->values + group * 16 + 16);
        int32x4_t low = DOT_LANE(vdupq_n_s32(0), vld1q_s8(weights), first, 0);
        int32x4_t high = DOT_LANE(vdupq_n_s32(0), vld1q_s8(weights + 64), second, 0);
        low = DOT_LANE(low, vld1q_s8(weights + 16), first, 1);
        high = DOT_LANE(high, vld1q_s8(weights + 80), second, 1);
        low = DOT_LANE(low, vld1q_s8(weights + 32), first, 2);
        high = DOT_LANE(high, vld1q_s8(weights + 96), second, 2);
        low = DOT_LANE(low, vld1q_s8(weights + 48), first, 3);
        high = DOT_LANE(high, vld1q_s8(weights + 112), second, 3);
        even = vmlaq_s32(even, low, vld1q_s32(tile->scales[group]));
        odd = vmlaq_s32(odd, high, vld1q_s32(tile->scales[group + 1]));
    }
    int32x4_t offsets = vdupq_n_s32(0);
    for (int index = 0; index < 8; index++) {
        int sum = input->sums[index * 2] + input->sums[index * 2 + 1];
        offsets = vmlaq_n_s32(offsets, vld1q_s32(tile->mins[index]), sum);
    }
    float32x4_t products = vmulq_f32(vld1q_f32(tile->scale), vcvtq_f32_s32(vaddq_s32(even, odd)));
    products = vmlsq_f32(products, vld1q_f32(tile->min_scale), vcvtq_f32_s32(offsets));
    vst1q_f32(sums, vmlaq_n_f32(vld1q_f32(sums), products, input->scale));
}
#else
static void tile_products(const Tile *tile, const Quantized *input, float *sums) {
    for (int lane = 0; lane < 4; lane++) {
        int total = 0, offsets = 0;
        for (int group = 0; group < 16; group++) {
            int sum = 0;
            for (int index = group * 16; index < group * 16 + 16; index++) {
                sum += tile->values[(index / 4 * 4 + lane) * 4 + index % 4] *
                       input->values[index];
            }
            total += tile->scales[group][lane] * sum;
        }
        for (int index = 0; index < 8; index++) {
            offsets += tile->mins[index][lane] *
                       (input->sums[index * 2] + input->sums[index * 2 + 1]);
        }
        sums[lane] += input->scale *
                      (tile->scale[lane] * total - tile->min_scale[lane] * offsets);
    }
}
#endif

static void quantize(const float *input, Quantized *output, int columns) {
    for (int block = 0; block < columns / BLOCK; block++, input += BLOCK, output++) {
        float largest = 0;
        for (int index = 0; index < BLOCK; index++) {
            largest = fmaxf(largest, fabsf(input[index]));
        }
        float inverse = largest > 0 ? 127.0f / largest : 0;
        output->scale = largest / 127.0f;
        for (int group = 0; group < 16; group++) {
            int sum = 0;
            for (int index = group * 16; index < group * 16 + 16; index++) {
                float scaled = input[index] * inverse;
                int value = (int)(scaled + (scaled < 0 ? -0.5f : 0.5f));
                output->values[index] = (int8_t)(value > 127 ? 127 : value < -127 ? -127 : value);
                sum += output->values[index];
            }
            output->sums[group] = (int16_t)sum;
        }
    }
}

typedef struct {
    const Tensor *weight;
    const Quantized *input;
    float *output;
    int count;
    const int32_t *rows;
    int row_count;
    atomic_int next;
} Product;

static void pack_q4(const uint8_t *const *blocks, PackedQ4 *packed) {
    int8_t values[4][BLOCK];
    for (int lane = 0; lane < 4; lane++) {
        int scales[8], mins[8];
        unpack_q4_k(blocks[lane], values[lane], scales, mins);
        for (int index = 0; index < 8; index++) {
            packed->scales[index][lane] = (int16_t)scales[index];
            packed->mins[index][lane] = (int16_t)mins[index];
        }
        packed->scale[lane] = half(blocks[lane]);
        packed->min_scale[lane] = half(blocks[lane] + 2);
    }
    for (int chunk = 0; chunk < 4; chunk++) {
        for (int group = 0; group < 8; group++) {
            for (int lane = 0; lane < 4; lane++) {
                for (int item = 0; item < 4; item++) {
                    int low = values[lane][chunk * 64 + group * 4 + item];
                    int high = values[lane][chunk * 64 + 32 + group * 4 + item];
                    packed->nibbles[chunk * 128 + group * 16 + lane * 4 + item] =
                        (uint8_t)(low | high << 4);
                }
            }
        }
    }
}

static void pack_q6(const uint8_t *const *blocks, PackedQ6 *packed) {
    int8_t values[4][BLOCK];
    for (int lane = 0; lane < 4; lane++) {
        unpack_q6_k(blocks[lane], values[lane]);
        const int8_t *scales = (const int8_t *)(blocks[lane] + 192);
        for (int index = 0; index < 16; index++) packed->scales[index][lane] = scales[index];
        packed->scale[lane] = half(blocks[lane] + 208);
    }
    for (int group = 0; group < BLOCK / 4; group++) {
        for (int lane = 0; lane < 4; lane++) {
            memcpy(packed->values + (group * 4 + lane) * 4, values[lane] + group * 4, 4);
        }
    }
}

#if defined(__aarch64__)
static void packed_q4_products(const PackedQ4 *packed, const Quantized *input, float *sums) {
    uint8x16_t mask = vdupq_n_u8(15);
    int32x4_t total = vdupq_n_s32(0);
    for (int chunk = 0; chunk < 4; chunk++) {
        const uint8_t *nibbles = packed->nibbles + chunk * 128;
        const int8_t *values = input->values + chunk * 64;
        int8x16_t inputs[4] = {vld1q_s8(values), vld1q_s8(values + 16), vld1q_s8(values + 32),
                               vld1q_s8(values + 48)};
        int32x4_t low = vdupq_n_s32(0), high = vdupq_n_s32(0);
        for (int group = 0; group < 8; group += 4) {
            int8x16_t first = inputs[group / 4], second = inputs[2 + group / 4];
            uint8x16_t bytes[4];
            for (int lane = 0; lane < 4; lane++) {
                bytes[lane] = vld1q_u8(nibbles + (group + lane) * 16);
            }
            low = DOT_LANE(low, vreinterpretq_s8_u8(vandq_u8(bytes[0], mask)), first, 0);
            high = DOT_LANE(high, vreinterpretq_s8_u8(vshrq_n_u8(bytes[0], 4)), second, 0);
            low = DOT_LANE(low, vreinterpretq_s8_u8(vandq_u8(bytes[1], mask)), first, 1);
            high = DOT_LANE(high, vreinterpretq_s8_u8(vshrq_n_u8(bytes[1], 4)), second, 1);
            low = DOT_LANE(low, vreinterpretq_s8_u8(vandq_u8(bytes[2], mask)), first, 2);
            high = DOT_LANE(high, vreinterpretq_s8_u8(vshrq_n_u8(bytes[2], 4)), second, 2);
            low = DOT_LANE(low, vreinterpretq_s8_u8(vandq_u8(bytes[3], mask)), first, 3);
            high = DOT_LANE(high, vreinterpretq_s8_u8(vshrq_n_u8(bytes[3], 4)), second, 3);
        }
        total = vmlaq_s32(total, low, vmovl_s16(vld1_s16(packed->scales[chunk * 2])));
        total = vmlaq_s32(total, high, vmovl_s16(vld1_s16(packed->scales[chunk * 2 + 1])));
    }
    int32x4_t offsets = vdupq_n_s32(0);
    for (int index = 0; index < 8; index++) {
        int sum = input->sums[index * 2] + input->sums[index * 2 + 1];
        offsets = vmlaq_n_s32(offsets, vmovl_s16(vld1_s16(packed->mins[index])), sum);
    }
    float32x4_t products = vmulq_f32(vld1q_f32(packed->scale), vcvtq_f32_s32(total));
    products = vmlsq_f32(products, vld1q_f32(packed->min_scale), vcvtq_f32_s32(offsets));
    vst1q_f32(sums, vmlaq_n_f32(vld1q_f32(sums), products, input->scale));
}

static float32x4_t finish_q4(const PackedQ4 *packed, const Quantized *input, int32x4_t total) {
    int32x4_t offsets = vdupq_n_s32(0);
    for (int index = 0; index < 8; index++) {
        int sum = input->sums[index * 2] + input->sums[index * 2 + 1];
        offsets = vmlaq_n_s32(offsets, vmovl_s16(vld1_s16(packed->mins[index])), sum);
    }
    float32x4_t products = vmulq_f32(vld1q_f32(packed->scale), vcvtq_f32_s32(total));
    products = vmlsq_f32(products, vld1q_f32(packed->min_scale), vcvtq_f32_s32(offsets));
    return vmulq_n_f32(products, input->scale);
}

static void packed_q4_products4(const PackedQ4 *packed, const Quantized *const *inputs,
                                float (*sums)[4]) {
    uint8x16_t mask = vdupq_n_u8(15);
    int32x4_t totals[4] = {vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0)};
    for (int chunk = 0; chunk < 4; chunk++) {
        const uint8_t *nibbles = packed->nibbles + chunk * 128;
        int32x4_t lows[4], highs[4];
        for (int token = 0; token < 4; token++) lows[token] = highs[token] = vdupq_n_s32(0);
        for (int quarter = 0; quarter < 2; quarter++) {
            int8x16_t low_weights[4], high_weights[4];
            for (int lane = 0; lane < 4; lane++) {
                uint8x16_t bytes = vld1q_u8(nibbles + (quarter * 4 + lane) * 16);
                low_weights[lane] = vreinterpretq_s8_u8(vandq_u8(bytes, mask));
                high_weights[lane] = vreinterpretq_s8_u8(vshrq_n_u8(bytes, 4));
            }
            for (int token = 0; token < 4; token++) {
                const int8_t *values = inputs[token]->values + chunk * 64 + quarter * 16;
                int8x16_t first = vld1q_s8(values), second = vld1q_s8(values + 32);
                lows[token] = DOT_LANE(lows[token], low_weights[0], first, 0);
                highs[token] = DOT_LANE(highs[token], high_weights[0], second, 0);
                lows[token] = DOT_LANE(lows[token], low_weights[1], first, 1);
                highs[token] = DOT_LANE(highs[token], high_weights[1], second, 1);
                lows[token] = DOT_LANE(lows[token], low_weights[2], first, 2);
                highs[token] = DOT_LANE(highs[token], high_weights[2], second, 2);
                lows[token] = DOT_LANE(lows[token], low_weights[3], first, 3);
                highs[token] = DOT_LANE(highs[token], high_weights[3], second, 3);
            }
        }
        int32x4_t low_scale = vmovl_s16(vld1_s16(packed->scales[chunk * 2]));
        int32x4_t high_scale = vmovl_s16(vld1_s16(packed->scales[chunk * 2 + 1]));
        for (int token = 0; token < 4; token++) {
            totals[token] = vmlaq_s32(totals[token], lows[token], low_scale);
            totals[token] = vmlaq_s32(totals[token], highs[token], high_scale);
        }
    }
    for (int token = 0; token < 4; token++) {
        float32x4_t products = finish_q4(packed, inputs[token], totals[token]);
        vst1q_f32(sums[token], vaddq_f32(vld1q_f32(sums[token]), products));
    }
}

static void packed_q6_products4(const PackedQ6 *packed, const Quantized *const *inputs,
                                float (*sums)[4]) {
    int32x4_t totals[4] = {vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0), vdupq_n_s32(0)};
    for (int group = 0; group < 16; group++) {
        const int8_t *weights = packed->values + group * 64;
        int8x16_t lanes[4] = {vld1q_s8(weights), vld1q_s8(weights + 16), vld1q_s8(weights + 32),
                              vld1q_s8(weights + 48)};
        int32x4_t scale = vmovl_s16(vld1_s16(packed->scales[group]));
        for (int token = 0; token < 4; token++) {
            int8x16_t values = vld1q_s8(inputs[token]->values + group * 16);
            int32x4_t sum = DOT_LANE(vdupq_n_s32(0), lanes[0], values, 0);
            sum = DOT_LANE(sum, lanes[1], values, 1);
            sum = DOT_LANE(sum, lanes[2], values, 2);
            sum = DOT_LANE(sum, lanes[3], values, 3);
            totals[token] = vmlaq_s32(totals[token], sum, scale);
        }
    }
    for (int token = 0; token < 4; token++) {
        float32x4_t products = vmulq_n_f32(vld1q_f32(packed->scale), inputs[token]->scale);
        products = vmulq_f32(products, vcvtq_f32_s32(totals[token]));
        vst1q_f32(sums[token], vaddq_f32(vld1q_f32(sums[token]), products));
    }
}

static void packed_q6_products(const PackedQ6 *packed, const Quantized *input, float *sums) {
    int32x4_t even = vdupq_n_s32(0), odd = vdupq_n_s32(0);
    for (int group = 0; group < 16; group += 2) {
        const int8_t *weights = packed->values + group * 64;
        int8x16_t first = vld1q_s8(input->values + group * 16);
        int8x16_t second = vld1q_s8(input->values + group * 16 + 16);
        int32x4_t low = DOT_LANE(vdupq_n_s32(0), vld1q_s8(weights), first, 0);
        int32x4_t high = DOT_LANE(vdupq_n_s32(0), vld1q_s8(weights + 64), second, 0);
        low = DOT_LANE(low, vld1q_s8(weights + 16), first, 1);
        high = DOT_LANE(high, vld1q_s8(weights + 80), second, 1);
        low = DOT_LANE(low, vld1q_s8(weights + 32), first, 2);
        high = DOT_LANE(high, vld1q_s8(weights + 96), second, 2);
        low = DOT_LANE(low, vld1q_s8(weights + 48), first, 3);
        high = DOT_LANE(high, vld1q_s8(weights + 112), second, 3);
        even = vmlaq_s32(even, low, vmovl_s16(vld1_s16(packed->scales[group])));
        odd = vmlaq_s32(odd, high, vmovl_s16(vld1_s16(packed->scales[group + 1])));
    }
    float32x4_t products = vmulq_f32(vld1q_f32(packed->scale), vcvtq_f32_s32(vaddq_s32(even, odd)));
    vst1q_f32(sums, vmlaq_n_f32(vld1q_f32(sums), products, input->scale));
}
#else
static void packed_q4_products(const PackedQ4 *packed, const Quantized *input, float *sums) {
    for (int lane = 0; lane < 4; lane++) {
        int total = 0, offsets = 0;
        for (int chunk = 0; chunk < 4; chunk++) {
            int low = 0, high = 0;
            for (int group = 0; group < 8; group++) {
                for (int item = 0; item < 4; item++) {
                    int byte = packed->nibbles[chunk * 128 + group * 16 + lane * 4 + item];
                    int at = chunk * 64 + group * 4 + item;
                    low += (byte & 15) * input->values[at];
                    high += (byte >> 4) * input->values[at + 32];
                }
            }
            total += packed->scales[chunk * 2][lane] * low;
            total += packed->scales[chunk * 2 + 1][lane] * high;
        }
        for (int index = 0; index < 8; index++) {
            offsets += packed->mins[index][lane] *
                       (input->sums[index * 2] + input->sums[index * 2 + 1]);
        }
        sums[lane] += input->scale *
                      (packed->scale[lane] * total - packed->min_scale[lane] * offsets);
    }
}

static void packed_q6_products(const PackedQ6 *packed, const Quantized *input, float *sums) {
    for (int lane = 0; lane < 4; lane++) {
        int total = 0;
        for (int group = 0; group < 16; group++) {
            int sum = 0;
            for (int index = group * 16; index < group * 16 + 16; index++) {
                sum += packed->values[(index / 4 * 4 + lane) * 4 + index % 4] *
                       input->values[index];
            }
            total += packed->scales[group][lane] * sum;
        }
        sums[lane] += input->scale * packed->scale[lane] * total;
    }
}
#endif

static void multiply_packed(const Product *product, int first) {
    const Tensor *weight = product->weight;
    int blocks = (int)(weight->columns / BLOCK);
    float sums[BATCH][4] = {{0}};
    int grouped = product->count / 4 * 4;
    for (int block = 0; block < blocks; block++) {
        const Quantized *input = product->input + block;
        size_t tile = (size_t)first / 4 * blocks + block;
        const PackedQ4 *packed_q4 = (const PackedQ4 *)weight->packed + tile;
        const PackedQ6 *packed_q6 = (const PackedQ6 *)weight->packed + tile;
#if defined(__aarch64__)
        for (int token = 0; token < grouped; token += 4) {
            const Quantized *inputs[4];
            for (int item = 0; item < 4; item++) {
                inputs[item] = input + (size_t)(token + item) * blocks;
            }
            if (weight->type == TYPE_Q4_K) packed_q4_products4(packed_q4, inputs, sums + token);
            else packed_q6_products4(packed_q6, inputs, sums + token);
        }
#else
        grouped = 0;
#endif
        for (int token = grouped; token < product->count; token++) {
            const Quantized *single = input + (size_t)token * blocks;
            if (weight->type == TYPE_Q4_K) packed_q4_products(packed_q4, single, sums[token]);
            else packed_q6_products(packed_q6, single, sums[token]);
        }
    }
    for (int token = 0; token < product->count; token++) {
        memcpy(product->output + (int64_t)token * product->row_count + first, sums[token],
               4 * sizeof(float));
    }
}

static void multiply_tile(const Product *product, int first) {
    const Tensor *weight = product->weight;
    if (weight->packed) {
        multiply_packed(product, first);
        return;
    }
    int blocks = (int)(weight->columns / BLOCK);
    int block_bytes = weight->type == TYPE_Q4_K ? Q4_K_BYTES : Q6_K_BYTES;
    const uint8_t *data[4];
    for (int lane = 0; lane < 4; lane++) {
        int index = first + lane < product->row_count ? first + lane : product->row_count - 1;
        int64_t row = product->rows ? product->rows[index] : index;
        data[lane] = weight->data + row * row_bytes(weight);
    }
    float sums[BATCH][4] = {{0}};
    Tile tile;
    for (int block = 0; block < blocks; block++) {
        unpack_tile(weight->type, data, &tile);
        const Quantized *input = product->input + block;
        for (int token = 0; token < product->count; token++, input += blocks) {
            tile_products(&tile, input, sums[token]);
        }
        for (int lane = 0; lane < 4; lane++) data[lane] += block_bytes;
    }
    for (int token = 0; token < product->count; token++) {
        for (int lane = 0; lane < 4 && first + lane < product->row_count; lane++) {
            product->output[(int64_t)token * product->row_count + first + lane] =
                sums[token][lane];
        }
    }
}

static void multiply_part(void *argument, int index, int count) {
    (void)index;
    (void)count;
    Product *product = argument;
    for (;;) {
        int begin = atomic_fetch_add(&product->next, CHUNK);
        if (begin >= product->row_count) return;
        int end = begin + CHUNK < product->row_count ? begin + CHUNK : product->row_count;
        for (int first = begin; first < end; first += 4) multiply_tile(product, first);
    }
}

static void multiply(Speaker *speaker, const Tensor *weight, const float *input, int count,
                     float *output) {
    int blocks = (int)(weight->columns / BLOCK);
    for (int token = 0; token < count; token++) {
        quantize(input + (int64_t)token * weight->columns,
                 speaker->quantized + token * blocks, (int)weight->columns);
    }
    Product product = {weight, speaker->quantized, output, count, NULL, (int)weight->rows};
    run(&speaker->pool, multiply_part, &product);
}

static void normalize(const float *input, const Tensor *weight, float *output, int size,
                      float epsilon) {
    const float *scale = (const float *)weight->data;
    float sum = 0;
    for (int index = 0; index < size; index++) sum += input[index] * input[index];
    float factor = 1.0f / sqrtf(sum / size + epsilon);
    for (int index = 0; index < size; index++) output[index] = input[index] * factor * scale[index];
}

static void rotate(float *head, const float *table) {
    for (int index = 0; index < HEAD / 2; index++) {
        float cosine = table[index * 2], sine = table[index * 2 + 1];
        float first = head[index], second = head[index + HEAD / 2];
        head[index] = first * cosine - second * sine;
        head[index + HEAD / 2] = first * sine + second * cosine;
    }
}

static void embed(const Speaker *speaker, int32_t token, float *output) {
    const Tensor *table = speaker->embedding;
    const uint8_t *data = table->data + token * row_bytes(table);
    int8_t values[BLOCK];
    for (int block = 0; block < speaker->width / BLOCK; block++, data += Q6_K_BYTES) {
        unpack_q6_k(data, values);
        const int8_t *scales = (const int8_t *)(data + 192);
        float scale = half(data + 208);
        for (int index = 0; index < BLOCK; index++) {
            output[block * BLOCK + index] = scale * scales[index / 16] * values[index];
        }
    }
}

typedef struct {
    Speaker *speaker;
    int layer;
    int count;
    int position;
    atomic_int next;
} Attention;

#if defined(__aarch64__)
static float dot_half(const float *query, const _Float16 *key) {
    float32x4_t sums[4] = {vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0)};
    for (int index = 0; index < HEAD; index += 16) {
        float16x8_t first = vld1q_f16((const __fp16 *)key + index);
        float16x8_t second = vld1q_f16((const __fp16 *)key + index + 8);
        sums[0] = vfmaq_f32(sums[0], vld1q_f32(query + index), vcvt_f32_f16(vget_low_f16(first)));
        sums[1] = vfmaq_f32(sums[1], vld1q_f32(query + index + 4), vcvt_high_f32_f16(first));
        sums[2] =
            vfmaq_f32(sums[2], vld1q_f32(query + index + 8), vcvt_f32_f16(vget_low_f16(second)));
        sums[3] = vfmaq_f32(sums[3], vld1q_f32(query + index + 12), vcvt_high_f32_f16(second));
    }
    return vaddvq_f32(vaddq_f32(vaddq_f32(sums[0], sums[1]), vaddq_f32(sums[2], sums[3])));
}

static void add_half(float *output, float weight, const _Float16 *value) {
    for (int index = 0; index < HEAD; index += 8) {
        float16x8_t values = vld1q_f16((const __fp16 *)value + index);
        float32x4_t low = vld1q_f32(output + index);
        float32x4_t high = vld1q_f32(output + index + 4);
        vst1q_f32(output + index, vfmaq_n_f32(low, vcvt_f32_f16(vget_low_f16(values)), weight));
        vst1q_f32(output + index + 4, vfmaq_n_f32(high, vcvt_high_f32_f16(values), weight));
    }
}
#else
static float dot_half(const float *query, const _Float16 *key) {
    float sum = 0;
    for (int index = 0; index < HEAD; index++) sum += query[index] * (float)key[index];
    return sum;
}

static void add_half(float *output, float weight, const _Float16 *value) {
    for (int index = 0; index < HEAD; index++) output[index] += weight * (float)value[index];
}
#endif

static void attend_head(const Attention *attention, int token, int head, float *scores) {
    Speaker *speaker = attention->speaker;
    int kv_width = speaker->kv_heads * HEAD;
    int group = head / (speaker->heads / speaker->kv_heads);
    int last = attention->position + token;
    size_t layer_offset = (size_t)attention->layer * speaker->context * kv_width;
    const float *query = speaker->query + (size_t)token * speaker->width + head * HEAD;
    float largest = -INFINITY;
    for (int past = 0; past <= last; past++) {
        const _Float16 *key = speaker->keys + layer_offset + (size_t)past * kv_width + group * HEAD;
        scores[past] = dot_half(query, key) / sqrtf(HEAD);
        largest = fmaxf(largest, scores[past]);
    }
    float total = 0;
    for (int past = 0; past <= last; past++) {
        scores[past] = fast_exp(scores[past] - largest);
        total += scores[past];
    }
    float *output = speaker->attended + (size_t)token * speaker->width + head * HEAD;
    memset(output, 0, HEAD * sizeof(float));
    for (int past = 0; past <= last; past++) {
        const _Float16 *value =
            speaker->values + layer_offset + (size_t)past * kv_width + group * HEAD;
        add_half(output, scores[past] / total, value);
    }
}

static void attend_part(void *argument, int index, int count) {
    (void)count;
    Attention *attention = argument;
    Speaker *speaker = attention->speaker;
    float *scores = speaker->scores + (size_t)index * speaker->context;
    int tasks = attention->count * speaker->heads;
    for (int task = atomic_fetch_add(&attention->next, 1); task < tasks;
         task = atomic_fetch_add(&attention->next, 1)) {
        attend_head(attention, task / speaker->heads, task % speaker->heads, scores);
    }
}

static void store_keys(Speaker *speaker, const Layer *layer, int layer_index, int count,
                       int position) {
    int kv_width = speaker->kv_heads * HEAD;
    for (int token = 0; token < count; token++) {
        float *query = speaker->query + (size_t)token * speaker->width;
        float *key = speaker->key + (size_t)token * kv_width;
        const float *table = speaker->rope + (size_t)(position + token) * HEAD;
        for (int head = 0; head < speaker->heads; head++) {
            float *part = query + head * HEAD;
            normalize(part, layer->query_norm, part, HEAD, speaker->epsilon);
            rotate(part, table);
        }
        for (int head = 0; head < speaker->kv_heads; head++) {
            float *part = key + head * HEAD;
            normalize(part, layer->key_norm, part, HEAD, speaker->epsilon);
            rotate(part, table);
        }
        size_t at = ((size_t)layer_index * speaker->context + position + token) * kv_width;
        const float *value = speaker->value + (size_t)token * kv_width;
        for (int index = 0; index < kv_width; index++) {
            speaker->keys[at + index] = (_Float16)key[index];
            speaker->values[at + index] = (_Float16)value[index];
        }
    }
}

static void add(float *target, const float *source, size_t size) {
    for (size_t index = 0; index < size; index++) target[index] += source[index];
}

static void feed_forward(Speaker *speaker, const Layer *layer, int count) {
    size_t width = (size_t)count * speaker->width;
    for (int token = 0; token < count; token++) {
        size_t offset = (size_t)token * speaker->width;
        normalize(speaker->x + offset, layer->ffn_norm, speaker->normed + offset,
                  speaker->width, speaker->epsilon);
    }
    multiply(speaker, layer->gate, speaker->normed, count, speaker->gate);
    multiply(speaker, layer->up, speaker->normed, count, speaker->up);
    for (size_t index = 0; index < (size_t)count * speaker->hidden; index++) {
        float gate = speaker->gate[index];
        speaker->gate[index] = gate / (1.0f + fast_exp(-gate)) * speaker->up[index];
    }
    multiply(speaker, layer->down, speaker->gate, count, speaker->down);
    add(speaker->x, speaker->down, width);
}

static void run_layer(Speaker *speaker, int layer_index, int count, int position) {
    const Layer *layer = &speaker->layers[layer_index];
    size_t width = (size_t)count * speaker->width;
    for (int token = 0; token < count; token++) {
        size_t offset = (size_t)token * speaker->width;
        normalize(speaker->x + offset, layer->attention_norm, speaker->normed + offset,
                  speaker->width, speaker->epsilon);
    }
    multiply(speaker, layer->query, speaker->normed, count, speaker->query);
    multiply(speaker, layer->key, speaker->normed, count, speaker->key);
    multiply(speaker, layer->value, speaker->normed, count, speaker->value);
    store_keys(speaker, layer, layer_index, count, position);
    Attention attention = {speaker, layer_index, count, position};
    run(&speaker->pool, attend_part, &attention);
    multiply(speaker, layer->output, speaker->attended, count, speaker->normed);
    add(speaker->x, speaker->normed, width);
    feed_forward(speaker, layer, count);
}

int speaker_evaluate(Speaker *speaker, const int32_t *tokens, int count, int position) {
    if (position + count > speaker->context) return 0;
    for (int start = 0; start < count; start += BATCH) {
        int batch = count - start < BATCH ? count - start : BATCH;
        for (int token = 0; token < batch; token++) {
            embed(speaker, tokens[start + token], speaker->x + (size_t)token * speaker->width);
        }
        for (int layer = 0; layer < speaker->layer_count; layer++) {
            run_layer(speaker, layer, batch, position + start);
        }
        memcpy(speaker->last, speaker->x + (size_t)(batch - 1) * speaker->width,
               speaker->width * sizeof(float));
    }
    return 1;
}

const float *speaker_logits(Speaker *speaker, const int32_t *allowed, int allowed_count) {
    normalize(speaker->last, speaker->output_norm, speaker->normed, speaker->width,
              speaker->epsilon);
    quantize(speaker->normed, speaker->quantized, speaker->width);
    int rows = allowed ? allowed_count : speaker->vocabulary;
    float *scratch = allowed ? speaker->gate : speaker->logits;
    Product product = {speaker->embedding, speaker->quantized, scratch, 1, allowed, rows};
    run(&speaker->pool, multiply_part, &product);
    if (!allowed) return speaker->logits;
    for (int index = 0; index < speaker->vocabulary; index++) speaker->logits[index] = -INFINITY;
    for (int index = 0; index < allowed_count; index++) {
        speaker->logits[allowed[index]] = scratch[index];
    }
    return speaker->logits;
}

int speaker_vocabulary(const Speaker *speaker) {
    return speaker->vocabulary;
}

typedef struct {
    Tensor *tensor;
    atomic_int next;
} Packing;

static void pack_part(void *argument, int index, int count) {
    (void)index;
    (void)count;
    Packing *packing = argument;
    Tensor *tensor = packing->tensor;
    int blocks = (int)(tensor->columns / BLOCK);
    int block_bytes = tensor->type == TYPE_Q4_K ? Q4_K_BYTES : Q6_K_BYTES;
    int tiles = (int)(tensor->rows / 4);
    for (int tile = atomic_fetch_add(&packing->next, 1); tile < tiles;
         tile = atomic_fetch_add(&packing->next, 1)) {
        for (int block = 0; block < blocks; block++) {
            const uint8_t *data[4];
            for (int lane = 0; lane < 4; lane++) {
                data[lane] = tensor->data + (int64_t)(tile * 4 + lane) * row_bytes(tensor) +
                             block * block_bytes;
            }
            size_t at = (size_t)tile * blocks + block;
            if (tensor->type == TYPE_Q4_K) pack_q4(data, (PackedQ4 *)tensor->packed + at);
            else pack_q6(data, (PackedQ6 *)tensor->packed + at);
        }
    }
}

static int pack_layers(Speaker *speaker) {
    for (int index = 0; index < speaker->tensor_count; index++) {
        Tensor *tensor = &speaker->tensors[index];
        int quantized = tensor->type == TYPE_Q4_K || tensor->type == TYPE_Q6_K;
        if (strncmp(tensor->name, "blk.", 4) != 0 || !quantized) continue;
        size_t tiles = (size_t)(tensor->rows / 4) * (tensor->columns / BLOCK);
        size_t size = tensor->type == TYPE_Q4_K ? sizeof(PackedQ4) : sizeof(PackedQ6);
        tensor->packed = malloc(tiles * size);
        if (!tensor->packed) return 0;
        Packing packing = {tensor, 0};
        run(&speaker->pool, pack_part, &packing);
    }
    return 1;
}

static void drop_packed_sources(const Speaker *speaker) {
    uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    for (int index = 0; index < speaker->tensor_count; index++) {
        const Tensor *tensor = &speaker->tensors[index];
        if (!tensor->packed) continue;
        uintptr_t first = (uintptr_t)tensor->data;
        uintptr_t start = (first + page - 1) / page * page;
        uintptr_t end = (first + tensor->rows * row_bytes(tensor)) / page * page;
        if (end > start) madvise((void *)start, end - start, MADV_DONTNEED);
    }
}

static int bind_layers(Speaker *speaker) {
    speaker->layers = calloc(speaker->layer_count, sizeof(Layer));
    for (int index = 0; index < speaker->layer_count; index++) {
        Layer *layer = &speaker->layers[index];
        layer->attention_norm = find(speaker, "attn_norm", index);
        layer->query = find(speaker, "attn_q", index);
        layer->key = find(speaker, "attn_k", index);
        layer->value = find(speaker, "attn_v", index);
        layer->output = find(speaker, "attn_output", index);
        layer->query_norm = find(speaker, "attn_q_norm", index);
        layer->key_norm = find(speaker, "attn_k_norm", index);
        layer->ffn_norm = find(speaker, "ffn_norm", index);
        layer->gate = find(speaker, "ffn_gate", index);
        layer->up = find(speaker, "ffn_up", index);
        layer->down = find(speaker, "ffn_down", index);
        const Tensor **fields = (const Tensor **)layer;
        for (size_t field = 0; field < sizeof(Layer) / sizeof(Tensor *); field++) {
            if (!fields[field]) return 0;
        }
    }
    speaker->embedding = find(speaker, "token_embd.weight", -1);
    speaker->output_norm = find(speaker, "output_norm.weight", -1);
    return speaker->embedding && speaker->output_norm;
}

static void allocate(Speaker *speaker, int threads) {
    size_t batch_width = (size_t)BATCH * speaker->width;
    size_t kv_width = (size_t)speaker->kv_heads * HEAD;
    size_t cache = (size_t)speaker->layer_count * speaker->context * kv_width;
    speaker->keys = calloc(cache, sizeof(_Float16));
    speaker->values = calloc(cache, sizeof(_Float16));
    speaker->x = calloc(batch_width, sizeof(float));
    speaker->normed = calloc(batch_width, sizeof(float));
    speaker->query = calloc(batch_width, sizeof(float));
    speaker->attended = calloc(batch_width, sizeof(float));
    speaker->key = calloc(BATCH * kv_width, sizeof(float));
    speaker->value = calloc(BATCH * kv_width, sizeof(float));
    size_t hidden = (size_t)BATCH * speaker->hidden;
    size_t gate = hidden > (size_t)speaker->vocabulary ? hidden : (size_t)speaker->vocabulary;
    speaker->gate = calloc(gate, sizeof(float));
    speaker->up = calloc(hidden, sizeof(float));
    speaker->down = calloc(batch_width, sizeof(float));
    speaker->scores = calloc((size_t)threads * speaker->context, sizeof(float));
    speaker->last = calloc(speaker->width, sizeof(float));
    speaker->logits = calloc(speaker->vocabulary, sizeof(float));
    speaker->quantized = calloc(hidden / BLOCK, sizeof(Quantized));
    speaker->rope = calloc((size_t)speaker->context * HEAD, sizeof(float));
    for (int position = 0; position < speaker->context; position++) {
        for (int index = 0; index < HEAD / 2; index++) {
            float angle = position * powf(speaker->rope_base, -2.0f * index / HEAD);
            speaker->rope[(size_t)position * HEAD + index * 2] = cosf(angle);
            speaker->rope[(size_t)position * HEAD + index * 2 + 1] = sinf(angle);
        }
    }
}

Speaker *speaker_open(const char *path, int context, int threads) {
    int descriptor = open(path, O_RDONLY);
    if (descriptor < 0) return NULL;
    struct stat status;
    fstat(descriptor, &status);
    Speaker *speaker = calloc(1, sizeof(Speaker));
    speaker->file_size = (size_t)status.st_size;
    speaker->file = mmap(NULL, speaker->file_size, PROT_READ, MAP_PRIVATE, descriptor, 0);
    close(descriptor);
    speaker->rope_base = 10000;
    speaker->epsilon = 1e-6f;
    if (speaker->file == MAP_FAILED || !parse(speaker) || !bind_layers(speaker)) {
        speaker_close(speaker);
        return NULL;
    }
    threads = threads < 1 ? 1 : threads > MAX_THREADS ? MAX_THREADS : threads;
    speaker->vocabulary = (int)speaker->embedding->rows;
    speaker->context = context;
    allocate(speaker, threads);
    start_pool(&speaker->pool, threads);
    if (!pack_layers(speaker)) {
        speaker_close(speaker);
        return NULL;
    }
    drop_packed_sources(speaker);
    return speaker;
}

void speaker_close(Speaker *speaker) {
    if (!speaker) return;
    if (speaker->pool.count > 0) stop_pool(&speaker->pool);
    if (speaker->file && speaker->file != MAP_FAILED) munmap(speaker->file, speaker->file_size);
    float *buffers[] = {speaker->x,      speaker->normed, speaker->query, speaker->attended,
                        speaker->key,    speaker->value,  speaker->gate,  speaker->up,
                        speaker->down,   speaker->scores, speaker->last,  speaker->logits,
                        speaker->rope};
    for (size_t index = 0; index < sizeof buffers / sizeof *buffers; index++) free(buffers[index]);
    for (int index = 0; index < speaker->tensor_count; index++) {
        free(speaker->tensors[index].packed);
    }
    free(speaker->keys);
    free(speaker->values);
    free(speaker->quantized);
    free(speaker->layers);
    free(speaker);
}
