#define PASTE(prefix, name) prefix##name
#define VARIANT_NAME(prefix, name) PASTE(prefix, name)
#define pocket_open VARIANT_NAME(VARIANT, pocket_open)
#define pocket_close VARIANT_NAME(VARIANT, pocket_close)
#define pocket_start VARIANT_NAME(VARIANT, pocket_start)
#define pocket_next VARIANT_NAME(VARIANT, pocket_next)

#include "pocket.h"
#include "pool.h"

#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

enum { TYPE_F32 = 0, TYPE_F16 = 1, TYPE_Q8_0 = 8, TYPE_I32 = 26 };
enum { QBLOCK = 32, Q8_BYTES = 34, LATENT = 32, HEAD = 64, CHUNK = 16 };
enum { UPSAMPLE = 16, MIMI_CONTEXT = 250, MIN_FRAMES = 6, MAX_LAYERS = 32, MAX_BLOCKS = 8 };
enum { FADE = 120, SCRATCH = 1 << 18, SPAN = 64 };

typedef struct {
    const char *name;
    int name_length;
    int type;
    int64_t shape[4];
    const uint8_t *data;
} Tensor;

typedef struct {
    float scale;
    int8_t values[QBLOCK];
} Quant;

typedef struct {
    int rows, columns;
    const uint8_t *quantized;
    float *dense;
    const float *bias;
} Linear;

typedef struct {
    Linear in_proj, out_proj, up, down;
    const float *norm1, *norm1_bias, *norm2, *norm2_bias, *scale1, *scale2;
} Layer;

typedef struct {
    Layer layers[MAX_LAYERS];
    int count, width, heads, hidden, context, capacity, length;
    float *keys, *values;
} Stack;

typedef struct {
    Linear first, second, modulation;
    const float *norm, *norm_bias;
} Block;

typedef struct {
    int in, out, kernel, stride, dilation, history;
    float *weight, *previous;
    const float *bias;
} Conv;

typedef struct {
    int in, out, kernel, stride;
    float *weight, *partial;
    const float *bias;
} ConvT;

typedef struct {
    Conv first, second;
} Residual;

struct Pocket {
    uint8_t *file;
    size_t file_size;
    Tensor *tensors;
    int tensor_count;
    const char *config;
    int config_length;
    Pool pool;
    int threads;

    Stack flow;
    const uint8_t *embedding;
    Linear input_linear, out_eos, cond_embed, input_proj, final_modulation, final_linear;
    const float *bos, *mean, *deviation, *out_norm, *out_norm_bias;
    Block blocks[MAX_BLOCKS];
    int block_count;
    float time[512];

    Linear quantizer;
    float *upsample, *upsample_partial;
    Stack mimi;
    Conv head, tail;
    ConvT ups[3];
    Residual residuals[3];

    float latent[LATENT];
    int started, first, step, eos_step, after, max_frames, done, faded;
    uint64_t random;
    float temperature;

    float *x, *normed, *mixed, *attended, *hidden, *buffer, *left, *right;
    Quant *quants;
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

static int key_is(const char *key, uint64_t length, const char *wanted) {
    return length == strlen(wanted) && memcmp(key, wanted, length) == 0;
}

static int parse(Pocket *pocket) {
    Reader reader = {pocket->file};
    if (pocket->file_size < 24 || memcmp(pocket->file, "GGUF", 4) != 0) return 0;
    reader.at += 8;
    uint64_t tensor_count = read_u64(&reader);
    uint64_t setting_count = read_u64(&reader);
    uint32_t alignment = 32;
    for (uint64_t index = 0; index < setting_count; index++) {
        uint64_t length = read_u64(&reader);
        const char *key = (const char *)reader.at;
        reader.at += length;
        uint32_t type = read_u32(&reader);
        if (type == 8 && key_is(key, length, "pocket.config")) {
            pocket->config_length = (int)read_u64(&reader);
            pocket->config = (const char *)reader.at;
            reader.at += pocket->config_length;
        } else if (type == 4 && key_is(key, length, "general.alignment")) {
            alignment = read_u32(&reader);
        } else {
            skip_value(&reader, type);
        }
    }
    pocket->tensors = calloc(tensor_count, sizeof(Tensor));
    uint64_t *offsets = calloc(tensor_count, sizeof(uint64_t));
    for (uint64_t index = 0; index < tensor_count; index++) {
        Tensor *tensor = &pocket->tensors[index];
        tensor->name_length = (int)read_u64(&reader);
        tensor->name = (const char *)reader.at;
        reader.at += tensor->name_length;
        uint32_t dimensions = read_u32(&reader);
        for (uint32_t dimension = 0; dimension < 4; dimension++) tensor->shape[dimension] = 1;
        for (uint32_t dimension = 0; dimension < dimensions; dimension++) {
            int64_t size = (int64_t)read_u64(&reader);
            if (dimension < 4) tensor->shape[dimension] = size;
        }
        tensor->type = (int)read_u32(&reader);
        offsets[index] = read_u64(&reader);
    }
    size_t header = (size_t)(reader.at - pocket->file);
    size_t data = (header + alignment - 1) / alignment * alignment;
    for (uint64_t index = 0; index < tensor_count; index++) {
        pocket->tensors[index].data = pocket->file + data + offsets[index];
    }
    free(offsets);
    pocket->tensor_count = (int)tensor_count;
    return pocket->config != NULL;
}

static const Tensor *find(const Pocket *pocket, const char *name) {
    int length = (int)strlen(name);
    for (int index = 0; index < pocket->tensor_count; index++) {
        const Tensor *tensor = &pocket->tensors[index];
        if (tensor->name_length == length && memcmp(tensor->name, name, length) == 0) {
            return tensor;
        }
    }
    return NULL;
}

static const Tensor *findf(const Pocket *pocket, const char *format, int number) {
    char name[160];
    snprintf(name, sizeof name, format, number);
    return find(pocket, name);
}

static int config_int(const Pocket *pocket, const char *section, const char *key) {
    const char *end = pocket->config + pocket->config_length;
    const char *at = pocket->config;
    size_t section_length = strlen(section), key_length = strlen(key);
    int inside = 0;
    while (at < end) {
        const char *line_end = memchr(at, '\n', (size_t)(end - at));
        if (!line_end) line_end = end;
        int indented = at < line_end && *at == ' ';
        if (!indented) {
            inside = (size_t)(line_end - at) > section_length &&
                     memcmp(at, section, section_length) == 0 && at[section_length] == ':';
        }
        const char *text = at;
        while (text < line_end && *text == ' ') text++;
        int matches = (size_t)(line_end - text) > key_length + 1 &&
                      memcmp(text, key, key_length) == 0 && text[key_length] == ':';
        if (inside && matches) return atoi(text + key_length + 1);
        at = line_end + 1;
    }
    return 0;
}

static float half(const uint8_t *bytes) {
    _Float16 value;
    memcpy(&value, bytes, 2);
    return (float)value;
}

static int64_t elements(const Tensor *tensor) {
    return tensor->shape[0] * tensor->shape[1] * tensor->shape[2] * tensor->shape[3];
}

static float *dense_copy(const Tensor *tensor) {
    int64_t count = elements(tensor);
    float *values = malloc((size_t)count * sizeof(float));
    for (int64_t index = 0; index < count; index++) {
        if (tensor->type == TYPE_F16) values[index] = half(tensor->data + index * 2);
        else memcpy(&values[index], tensor->data + index * 4, 4);
    }
    return values;
}

static int load_linear(const Pocket *pocket, Linear *linear, const char *prefix) {
    char name[160];
    snprintf(name, sizeof name, "%s.weight", prefix);
    const Tensor *weight = find(pocket, name);
    if (!weight) return 0;
    snprintf(name, sizeof name, "%s.bias", prefix);
    const Tensor *bias = find(pocket, name);
    linear->columns = (int)weight->shape[0];
    linear->rows = (int)(elements(weight) / weight->shape[0]);
    linear->bias = bias ? (const float *)bias->data : NULL;
    if (weight->type == TYPE_Q8_0) linear->quantized = weight->data;
    else linear->dense = dense_copy(weight);
    return 1;
}

static int load_linearf(const Pocket *pocket, Linear *linear, const char *format, int number) {
    char prefix[160];
    snprintf(prefix, sizeof prefix, format, number);
    return load_linear(pocket, linear, prefix);
}

static const float *vector(const Pocket *pocket, const char *format, int number) {
    const Tensor *tensor = findf(pocket, format, number);
    return tensor && tensor->type == TYPE_F32 ? (const float *)tensor->data : NULL;
}

static float dot(const float *first, const float *second, int size) {
    int index = 0;
    float sum = 0;
#if defined(__aarch64__)
    float32x4_t sums[4] = {vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0)};
    for (; index + 16 <= size; index += 16) {
        for (int lane = 0; lane < 4; lane++) {
            float32x4_t left = vld1q_f32(first + index + lane * 4);
            sums[lane] = vfmaq_f32(sums[lane], left, vld1q_f32(second + index + lane * 4));
        }
    }
    sum = vaddvq_f32(vaddq_f32(vaddq_f32(sums[0], sums[1]), vaddq_f32(sums[2], sums[3])));
#endif
    for (; index < size; index++) sum += first[index] * second[index];
    return sum;
}

static int dot_q8(const int8_t *first, const int8_t *second) {
#if defined(__ARM_FEATURE_DOTPROD)
    int32x4_t sum = vdotq_s32(vdupq_n_s32(0), vld1q_s8(first), vld1q_s8(second));
    sum = vdotq_s32(sum, vld1q_s8(first + 16), vld1q_s8(second + 16));
    return vaddvq_s32(sum);
#elif defined(__aarch64__)
    int8x16_t low = vld1q_s8(first), high = vld1q_s8(first + 16);
    int8x16_t other_low = vld1q_s8(second), other_high = vld1q_s8(second + 16);
    int16x8_t products = vmull_s8(vget_low_s8(low), vget_low_s8(other_low));
    products = vmlal_high_s8(products, low, other_low);
    int16x8_t more = vmull_s8(vget_low_s8(high), vget_low_s8(other_high));
    more = vmlal_high_s8(more, high, other_high);
    return vaddvq_s32(vpadalq_s16(vpaddlq_s16(products), more));
#else
    int sum = 0;
    for (int index = 0; index < QBLOCK; index++) sum += first[index] * second[index];
    return sum;
#endif
}

static float row_q8(const uint8_t *row, const Quant *input, int blocks) {
    float sum = 0;
    for (int block = 0; block < blocks; block++, row += Q8_BYTES) {
        int product = dot_q8((const int8_t *)(row + 2), input[block].values);
        sum += half(row) * input[block].scale * (float)product;
    }
    return sum;
}

static void quantize(const float *input, Quant *output, int size) {
    for (int block = 0; block < size / QBLOCK; block++, input += QBLOCK) {
        float largest = 0;
        for (int index = 0; index < QBLOCK; index++) largest = fmaxf(largest, fabsf(input[index]));
        float inverse = largest > 0 ? 127.0f / largest : 0;
        output[block].scale = largest / 127.0f;
        for (int index = 0; index < QBLOCK; index++) {
            output[block].values[index] = (int8_t)lrintf(input[index] * inverse);
        }
    }
}

typedef void (*Body)(void *context, int index);

typedef struct {
    Body body;
    void *context;
    int count;
    atomic_int next;
} Range;

static void range_part(void *argument, int index, int count) {
    (void)index;
    (void)count;
    Range *range = argument;
    for (;;) {
        int begin = atomic_fetch_add(&range->next, CHUNK);
        if (begin >= range->count) return;
        int end = begin + CHUNK < range->count ? begin + CHUNK : range->count;
        for (int item = begin; item < end; item++) range->body(range->context, item);
    }
}

static void parallel(Pocket *pocket, int count, Body body, void *context) {
    Range range = {body, context, count, 0};
    run(&pocket->pool, range_part, &range);
}

typedef struct {
    const Linear *linear;
    const float *input;
    const Quant *quants;
    float *output;
    int count;
} Product;

static void product_row(void *context, int row) {
    const Product *product = context;
    const Linear *linear = product->linear;
    int blocks = linear->columns / QBLOCK;
    float bias = linear->bias ? linear->bias[row] : 0;
    for (int token = 0; token < product->count; token++) {
        float sum = linear->quantized
            ? row_q8(linear->quantized + (size_t)row * blocks * Q8_BYTES,
                     product->quants + (size_t)token * blocks, blocks)
            : dot(linear->dense + (size_t)row * linear->columns,
                  product->input + (size_t)token * linear->columns, linear->columns);
        product->output[(size_t)token * linear->rows + row] = sum + bias;
    }
}

static void apply(Pocket *pocket, const Linear *linear, const float *input, int count,
                  float *output) {
    if (linear->quantized) {
        for (int token = 0; token < count; token++) {
            quantize(input + (size_t)token * linear->columns,
                     pocket->quants + (size_t)token * (linear->columns / QBLOCK), linear->columns);
        }
    }
    Product product = {linear, input, pocket->quants, output, count};
    parallel(pocket, linear->rows, product_row, &product);
}

static void layer_norm(const float *input, float *output, int size, const float *weight,
                       const float *bias, float epsilon) {
    float mean = 0, variance = 0;
    for (int index = 0; index < size; index++) mean += input[index];
    mean /= size;
    for (int index = 0; index < size; index++) {
        variance += (input[index] - mean) * (input[index] - mean);
    }
    float factor = 1.0f / sqrtf(variance / size + epsilon);
    for (int index = 0; index < size; index++) {
        float value = (input[index] - mean) * factor;
        output[index] = weight ? value * weight[index] + bias[index] : value;
    }
}

static float silu(float value) {
    return value / (1.0f + expf(-value));
}

static float gelu(float value) {
    float inner = 0.7978845608f * (value + 0.044715f * value * value * value);
    return 0.5f * value * (1.0f + tanhf(inner));
}

static float elu(float value) {
    return value > 0 ? value : expf(value) - 1.0f;
}

static void rotate(float *head, int position) {
    for (int pair = 0; pair < HEAD / 2; pair++) {
        float frequency = expf(-logf(10000.0f) * 2.0f * pair / HEAD);
        float angle = (float)position * frequency;
        float cosine = cosf(angle), sine = sinf(angle);
        float real = head[pair * 2], imaginary = head[pair * 2 + 1];
        head[pair * 2] = real * cosine - imaginary * sine;
        head[pair * 2 + 1] = real * sine + imaginary * cosine;
    }
}

typedef struct {
    const Stack *stack;
    int layer, count, start;
    const float *mixed;
    float *output;
} Attention;

static void attend(void *context, int task) {
    const Attention *attention = context;
    const Stack *stack = attention->stack;
    int token = task / stack->heads, head = task % stack->heads;
    int position = attention->start + token;
    int first = stack->context ? position - stack->context + 1 : 0;
    if (first < 0) first = 0;
    const float *query = attention->mixed + (size_t)token * 3 * stack->width + head * HEAD;
    size_t layer = (size_t)attention->layer * stack->capacity * stack->width;
    float scores[4096];
    float largest = -INFINITY;
    for (int past = first; past <= position; past++) {
        size_t at = layer + (size_t)(past % stack->capacity) * stack->width + head * HEAD;
        scores[past - first] = dot(query, stack->keys + at, HEAD) * 0.125f;
        largest = fmaxf(largest, scores[past - first]);
    }
    float total = 0;
    for (int past = first; past <= position; past++) {
        scores[past - first] = expf(scores[past - first] - largest);
        total += scores[past - first];
    }
    float *output = attention->output + (size_t)token * stack->width + head * HEAD;
    memset(output, 0, HEAD * sizeof(float));
    for (int past = first; past <= position; past++) {
        size_t at = layer + (size_t)(past % stack->capacity) * stack->width + head * HEAD;
        float weight = scores[past - first] / total;
        for (int index = 0; index < HEAD; index++) {
            output[index] += weight * stack->values[at + index];
        }
    }
}

static void add_scaled(float *target, const float *update, const float *scale, int width,
                       int count) {
    for (int token = 0; token < count; token++) {
        for (int index = 0; index < width; index++) {
            float value = update[(size_t)token * width + index];
            target[(size_t)token * width + index] += scale ? scale[index] * value : value;
        }
    }
}

static void store(Stack *stack, int layer, const float *mixed, int count) {
    for (int token = 0; token < count; token++) {
        int position = stack->length + token;
        float *row = (float *)mixed + (size_t)token * 3 * stack->width;
        for (int head = 0; head < stack->heads; head++) {
            rotate(row + head * HEAD, position);
            rotate(row + stack->width + head * HEAD, position);
        }
        size_t at = ((size_t)layer * stack->capacity + position % stack->capacity) * stack->width;
        memcpy(stack->keys + at, row + stack->width, stack->width * sizeof(float));
        memcpy(stack->values + at, row + 2 * stack->width, stack->width * sizeof(float));
    }
}

static void forward(Pocket *pocket, Stack *stack, float *x, int count) {
    int width = stack->width;
    for (int index = 0; index < stack->count; index++) {
        const Layer *layer = &stack->layers[index];
        for (int token = 0; token < count; token++) {
            layer_norm(x + (size_t)token * width, pocket->normed + (size_t)token * width, width,
                       layer->norm1, layer->norm1_bias, 1e-5f);
        }
        apply(pocket, &layer->in_proj, pocket->normed, count, pocket->mixed);
        store(stack, index, pocket->mixed, count);
        Attention attention = {stack, index, count, stack->length, pocket->mixed,
                               pocket->attended};
        parallel(pocket, count * stack->heads, attend, &attention);
        apply(pocket, &layer->out_proj, pocket->attended, count, pocket->normed);
        add_scaled(x, pocket->normed, layer->scale1, width, count);
        for (int token = 0; token < count; token++) {
            layer_norm(x + (size_t)token * width, pocket->normed + (size_t)token * width, width,
                       layer->norm2, layer->norm2_bias, 1e-5f);
        }
        apply(pocket, &layer->up, pocket->normed, count, pocket->hidden);
        size_t hidden = (size_t)count * stack->hidden;
        for (size_t at = 0; at < hidden; at++) pocket->hidden[at] = gelu(pocket->hidden[at]);
        apply(pocket, &layer->down, pocket->hidden, count, pocket->normed);
        add_scaled(x, pocket->normed, layer->scale2, width, count);
    }
    stack->length += count;
}

static int load_stack(const Pocket *pocket, Stack *stack, const char *prefix, int heads,
                      int context) {
    char format[160];
    stack->heads = heads;
    stack->context = context;
    for (int index = 0; index < MAX_LAYERS; index++) {
        Layer *layer = &stack->layers[index];
        snprintf(format, sizeof format, "%s.layers.%%d.self_attn.in_proj", prefix);
        if (!load_linearf(pocket, &layer->in_proj, format, index)) break;
        snprintf(format, sizeof format, "%s.layers.%%d.self_attn.out_proj", prefix);
        if (!load_linearf(pocket, &layer->out_proj, format, index)) return 0;
        snprintf(format, sizeof format, "%s.layers.%%d.linear1", prefix);
        if (!load_linearf(pocket, &layer->up, format, index)) return 0;
        snprintf(format, sizeof format, "%s.layers.%%d.linear2", prefix);
        if (!load_linearf(pocket, &layer->down, format, index)) return 0;
        const char *names[] = {"norm1.weight", "norm1.bias", "norm2.weight", "norm2.bias",
                               "layer_scale_1.scale", "layer_scale_2.scale"};
        const float **fields[] = {&layer->norm1, &layer->norm1_bias, &layer->norm2,
                                  &layer->norm2_bias, &layer->scale1, &layer->scale2};
        for (int field = 0; field < 6; field++) {
            snprintf(format, sizeof format, "%s.layers.%%d.%s", prefix, names[field]);
            *fields[field] = vector(pocket, format, index);
        }
        if (!layer->norm1 || !layer->norm2) return 0;
        stack->count = index + 1;
    }
    if (stack->count == 0) return 0;
    stack->width = stack->layers[0].out_proj.rows;
    stack->hidden = stack->layers[0].up.rows;
    return heads > 0 && stack->width == heads * HEAD;
}

static void reserve(Stack *stack, int capacity) {
    if (capacity <= stack->capacity) return;
    size_t size = (size_t)stack->count * capacity * stack->width * sizeof(float);
    free(stack->keys);
    free(stack->values);
    stack->keys = malloc(size);
    stack->values = malloc(size);
    stack->capacity = capacity;
}

static void reorder_conv(const Tensor *tensor, Conv *conv) {
    float *raw = dense_copy(tensor);
    conv->kernel = (int)tensor->shape[0];
    conv->in = (int)tensor->shape[1];
    conv->out = (int)tensor->shape[2];
    conv->weight = malloc((size_t)conv->out * conv->kernel * conv->in * sizeof(float));
    for (int out = 0; out < conv->out; out++) {
        for (int in = 0; in < conv->in; in++) {
            for (int tap = 0; tap < conv->kernel; tap++) {
                conv->weight[((size_t)out * conv->kernel + tap) * conv->in + in] =
                    raw[((size_t)out * conv->in + in) * conv->kernel + tap];
            }
        }
    }
    free(raw);
}

static int load_conv(const Pocket *pocket, Conv *conv, const char *prefix, int stride,
                     int dilation) {
    char name[160];
    snprintf(name, sizeof name, "%s.conv.weight", prefix);
    const Tensor *weight = find(pocket, name);
    snprintf(name, sizeof name, "%s.conv.bias", prefix);
    const Tensor *bias = find(pocket, name);
    if (!weight || !bias) return 0;
    reorder_conv(weight, conv);
    conv->bias = (const float *)bias->data;
    conv->stride = stride;
    conv->dilation = dilation;
    conv->history = (conv->kernel - 1) * dilation + 1 - stride;
    conv->previous = calloc((size_t)(conv->history + 1) * conv->in, sizeof(float));
    return 1;
}

static int load_convt(const Pocket *pocket, ConvT *conv, int index, int stride) {
    const Tensor *weight = findf(pocket, "mimi.decoder.model.%d.convtr.weight", index);
    const Tensor *bias = findf(pocket, "mimi.decoder.model.%d.convtr.bias", index);
    if (!weight || !bias) return 0;
    float *raw = dense_copy(weight);
    conv->kernel = (int)weight->shape[0];
    conv->out = (int)weight->shape[1];
    conv->in = (int)weight->shape[2];
    conv->stride = stride;
    conv->bias = (const float *)bias->data;
    conv->weight = malloc((size_t)conv->kernel * conv->out * conv->in * sizeof(float));
    for (int in = 0; in < conv->in; in++) {
        for (int out = 0; out < conv->out; out++) {
            for (int tap = 0; tap < conv->kernel; tap++) {
                conv->weight[((size_t)tap * conv->out + out) * conv->in + in] =
                    raw[((size_t)in * conv->out + out) * conv->kernel + tap];
            }
        }
    }
    free(raw);
    conv->partial = calloc((size_t)(conv->kernel - stride) * conv->out, sizeof(float));
    return 1;
}

static void dot4(const float *weight, const float *input, size_t stride, int size,
                 float *sums) {
    int index = 0;
#if defined(__aarch64__)
    float32x4_t totals[4] = {vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0), vdupq_n_f32(0)};
    for (; index + 4 <= size; index += 4) {
        float32x4_t values = vld1q_f32(weight + index);
        for (int row = 0; row < 4; row++) {
            float32x4_t other = vld1q_f32(input + row * stride + index);
            totals[row] = vfmaq_f32(totals[row], values, other);
        }
    }
    for (int row = 0; row < 4; row++) sums[row] += vaddvq_f32(totals[row]);
#endif
    for (; index < size; index++) {
        for (int row = 0; row < 4; row++) sums[row] += weight[index] * input[row * stride + index];
    }
}

typedef struct {
    const void *conv;
    const float *input;
    float *output;
    int steps, spans;
} ConvJob;

static void conv_item(void *context, int item) {
    const ConvJob *job = context;
    const Conv *conv = job->conv;
    int out = item / job->spans, first = item % job->spans * SPAN;
    int last = first + SPAN < job->steps ? first + SPAN : job->steps;
    size_t stride = (size_t)conv->stride * conv->in;
    for (int step = first; step < last; step += 4) {
        float sums[4] = {0, 0, 0, 0};
        int rows = last - step < 4 ? last - step : 4;
        for (int tap = 0; tap < conv->kernel; tap++) {
            const float *weight = conv->weight + ((size_t)out * conv->kernel + tap) * conv->in;
            const float *input =
                job->input + (size_t)(step * conv->stride + tap * conv->dilation) * conv->in;
            if (rows == 4) {
                dot4(weight, input, stride, conv->in, sums);
                continue;
            }
            for (int row = 0; row < rows; row++) {
                sums[row] += dot(weight, input + row * stride, conv->in);
            }
        }
        for (int row = 0; row < rows; row++) {
            job->output[(size_t)(step + row) * conv->out + out] = sums[row] + conv->bias[out];
        }
    }
}

static int conv_forward(Pocket *pocket, Conv *conv, const float *input, int steps,
                        float *output) {
    size_t history = (size_t)conv->history * conv->in;
    memcpy(pocket->buffer, conv->previous, history * sizeof(float));
    memcpy(pocket->buffer + history, input, (size_t)steps * conv->in * sizeof(float));
    int outputs = steps / conv->stride, spans = (outputs + SPAN - 1) / SPAN;
    ConvJob job = {conv, pocket->buffer, output, outputs, spans};
    parallel(pocket, conv->out * spans, conv_item, &job);
    memcpy(conv->previous, pocket->buffer + (size_t)steps * conv->in, history * sizeof(float));
    return outputs;
}

static void convt_item(void *context, int item) {
    const ConvJob *job = context;
    const ConvT *conv = job->conv;
    int out = item;
    for (int step = 0; step < job->steps; step += 4) {
        int rows = job->steps - step < 4 ? job->steps - step : 4;
        const float *input = job->input + (size_t)step * conv->in;
        for (int tap = 0; tap < conv->kernel; tap++) {
            const float *weight = conv->weight + ((size_t)tap * conv->out + out) * conv->in;
            float sums[4] = {0, 0, 0, 0};
            if (rows == 4) {
                dot4(weight, input, conv->in, conv->in, sums);
            } else {
                for (int row = 0; row < rows; row++) {
                    sums[row] = dot(weight, input + (size_t)row * conv->in, conv->in);
                }
            }
            for (int row = 0; row < rows; row++) {
                size_t at = (size_t)((step + row) * conv->stride + tap) * conv->out + out;
                job->output[at] += sums[row];
            }
        }
    }
}

static int convt_forward(Pocket *pocket, ConvT *conv, const float *input, int steps,
                         float *output) {
    int length = steps * conv->stride, tail = conv->kernel - conv->stride;
    for (int at = 0; at < length + tail; at++) {
        memcpy(output + (size_t)at * conv->out, conv->bias, conv->out * sizeof(float));
    }
    ConvJob job = {conv, input, output, steps, 1};
    parallel(pocket, conv->out, convt_item, &job);
    for (int at = 0; at < tail * conv->out; at++) output[at] += conv->partial[at];
    for (int at = 0; at < tail; at++) {
        for (int out = 0; out < conv->out; out++) {
            conv->partial[at * conv->out + out] =
                output[(size_t)(length + at) * conv->out + out] - conv->bias[out];
        }
    }
    return length;
}

static void elu_all(float *values, size_t count) {
    for (size_t index = 0; index < count; index++) values[index] = elu(values[index]);
}

static int residual_forward(Pocket *pocket, Residual *residual, float *x, int steps) {
    int width = residual->first.in;
    size_t size = (size_t)steps * width;
    float *activated = pocket->hidden;
    memcpy(activated, x, size * sizeof(float));
    elu_all(activated, size);
    conv_forward(pocket, &residual->first, activated, steps, pocket->mixed);
    elu_all(pocket->mixed, (size_t)steps * residual->first.out);
    conv_forward(pocket, &residual->second, pocket->mixed, steps, activated);
    for (size_t index = 0; index < size; index++) x[index] += activated[index];
    return steps;
}

static void decode(Pocket *pocket, const float *latent, float *samples) {
    float scaled[LATENT], embedded[512];
    for (int index = 0; index < LATENT; index++) {
        scaled[index] = latent[index] * pocket->deviation[index] + pocket->mean[index];
    }
    apply(pocket, &pocket->quantizer, scaled, 1, embedded);
    int width = pocket->quantizer.rows;
    float *x = pocket->left;
    for (int tap = 0; tap < 2 * UPSAMPLE; tap++) {
        for (int channel = 0; channel < width; channel++) {
            float value = embedded[channel] * pocket->upsample[channel * 2 * UPSAMPLE + tap];
            if (tap < UPSAMPLE) x[tap * width + channel] = value +
                                    pocket->upsample_partial[tap * width + channel];
            else pocket->upsample_partial[(tap - UPSAMPLE) * width + channel] = value;
        }
    }
    forward(pocket, &pocket->mimi, x, UPSAMPLE);
    int steps = conv_forward(pocket, &pocket->head, x, UPSAMPLE, pocket->right);
    float *current = pocket->right, *other = pocket->left;
    for (int stage = 0; stage < 3; stage++) {
        elu_all(current, (size_t)steps * pocket->ups[stage].in);
        steps = convt_forward(pocket, &pocket->ups[stage], current, steps, other);
        residual_forward(pocket, &pocket->residuals[stage], other, steps);
        float *swap = current;
        current = other;
        other = swap;
    }
    elu_all(current, (size_t)steps * pocket->tail.in);
    conv_forward(pocket, &pocket->tail, current, steps, samples);
    if (pocket->faded) return;
    for (int index = 0; index < FADE; index++) samples[index] *= (float)index / (FADE - 1);
    pocket->faded = 1;
}

static void time_embedding(Pocket *pocket, int index, float time, float *output) {
    const float *frequencies = vector(pocket, "flow_lm.flow_net.time_embed.%d.freqs", index);
    const float *alpha = vector(pocket, "flow_lm.flow_net.time_embed.%d.mlp.3.alpha", index);
    Linear first = {0}, second = {0};
    load_linearf(pocket, &first, "flow_lm.flow_net.time_embed.%d.mlp.0", index);
    load_linearf(pocket, &second, "flow_lm.flow_net.time_embed.%d.mlp.2", index);
    float embedding[256], hidden[512];
    for (int at = 0; at < 128; at++) {
        embedding[at] = cosf(time * frequencies[at]);
        embedding[at + 128] = sinf(time * frequencies[at]);
    }
    apply(pocket, &first, embedding, 1, hidden);
    for (int at = 0; at < 512; at++) hidden[at] = silu(hidden[at]);
    apply(pocket, &second, hidden, 1, output);
    float mean = 0, variance = 0;
    for (int at = 0; at < 512; at++) mean += output[at];
    mean /= 512;
    for (int at = 0; at < 512; at++) variance += (output[at] - mean) * (output[at] - mean);
    float factor = 1.0f / sqrtf(variance / 511 + 1e-5f);
    for (int at = 0; at < 512; at++) output[at] *= alpha[at] * factor;
    free(first.dense);
    free(second.dense);
}

static int load_flow(Pocket *pocket) {
    int heads = config_int(pocket, "flow_lm", "num_heads");
    if (!load_stack(pocket, &pocket->flow, "flow_lm.transformer", heads, 0)) return 0;
    const Tensor *embedding = find(pocket, "flow_lm.conditioner.embed.weight");
    if (!embedding || embedding->type != TYPE_Q8_0) return 0;
    if (find(pocket, "flow_lm.z_proj.weight")) return 0;
    pocket->embedding = embedding->data;
    int loaded = load_linear(pocket, &pocket->input_linear, "flow_lm.input_linear") &&
                 load_linear(pocket, &pocket->out_eos, "flow_lm.out_eos") &&
                 load_linear(pocket, &pocket->cond_embed, "flow_lm.flow_net.cond_embed") &&
                 load_linear(pocket, &pocket->input_proj, "flow_lm.flow_net.input_proj") &&
                 load_linear(pocket, &pocket->final_modulation,
                             "flow_lm.flow_net.final_layer.adaLN_modulation.1") &&
                 load_linear(pocket, &pocket->final_linear, "flow_lm.flow_net.final_layer.linear");
    pocket->bos = vector(pocket, "flow_lm.bos_emb", 0);
    pocket->mean = vector(pocket, "flow_lm.emb_mean", 0);
    pocket->deviation = vector(pocket, "flow_lm.emb_std", 0);
    pocket->out_norm = vector(pocket, "flow_lm.out_norm.weight", 0);
    pocket->out_norm_bias = vector(pocket, "flow_lm.out_norm.bias", 0);
    for (int index = 0; index < MAX_BLOCKS; index++) {
        Block *block = &pocket->blocks[index];
        const char *prefix = "flow_lm.flow_net.res_blocks.%d";
        char format[120];
        snprintf(format, sizeof format, "%s.mlp.0", prefix);
        if (!load_linearf(pocket, &block->first, format, index)) break;
        snprintf(format, sizeof format, "%s.mlp.2", prefix);
        load_linearf(pocket, &block->second, format, index);
        snprintf(format, sizeof format, "%s.adaLN_modulation.1", prefix);
        load_linearf(pocket, &block->modulation, format, index);
        block->norm = vector(pocket, "flow_lm.flow_net.res_blocks.%d.in_ln.weight", index);
        block->norm_bias = vector(pocket, "flow_lm.flow_net.res_blocks.%d.in_ln.bias", index);
        pocket->block_count = index + 1;
    }
    if (!loaded || !pocket->bos || !pocket->mean || !pocket->deviation || !pocket->out_norm) {
        return 0;
    }
    if (pocket->block_count == 0 || pocket->cond_embed.rows != 512) return 0;
    return 1;
}

static void load_time(Pocket *pocket) {
    float start[512], end[512];
    time_embedding(pocket, 0, 0.0f, start);
    time_embedding(pocket, 1, 1.0f, end);
    for (int at = 0; at < 512; at++) pocket->time[at] = (start[at] + end[at]) / 2;
}

static int load_mimi(Pocket *pocket) {
    int heads = config_int(pocket, "mimi", "num_heads");
    if (!load_stack(pocket, &pocket->mimi, "mimi.decoder_tf", heads, MIMI_CONTEXT)) return 0;
    reserve(&pocket->mimi, MIMI_CONTEXT + UPSAMPLE);
    const Tensor *quantizer = find(pocket, "mimi.quantizer.output_proj.weight");
    const Tensor *upsample = find(pocket, "mimi.upsample.convtr.convtr.weight");
    if (!quantizer || !upsample || upsample->shape[0] != 2 * UPSAMPLE) return 0;
    pocket->quantizer.columns = (int)quantizer->shape[1];
    pocket->quantizer.rows = (int)quantizer->shape[2];
    pocket->quantizer.dense = dense_copy(quantizer);
    pocket->upsample = dense_copy(upsample);
    pocket->upsample_partial = calloc((size_t)UPSAMPLE * pocket->quantizer.rows, sizeof(float));
    static const int strides[] = {6, 5, 4};
    int loaded = load_conv(pocket, &pocket->head, "mimi.decoder.model.0", 1, 1) &&
                 load_conv(pocket, &pocket->tail, "mimi.decoder.model.11", 1, 1);
    for (int stage = 0; loaded && stage < 3; stage++) {
        char prefix[80];
        loaded = load_convt(pocket, &pocket->ups[stage], 2 + stage * 3, strides[stage]);
        snprintf(prefix, sizeof prefix, "mimi.decoder.model.%d.block.1", 3 + stage * 3);
        loaded = loaded && load_conv(pocket, &pocket->residuals[stage].first, prefix, 1, 1);
        snprintf(prefix, sizeof prefix, "mimi.decoder.model.%d.block.3", 3 + stage * 3);
        loaded = loaded && load_conv(pocket, &pocket->residuals[stage].second, prefix, 1, 1);
    }
    return loaded && pocket->quantizer.columns == LATENT;
}

static void allocate(Pocket *pocket) {
    int width = pocket->flow.width > 1024 ? pocket->flow.width : 1024;
    size_t tokens = 512;
    pocket->x = calloc(tokens * width, sizeof(float));
    pocket->normed = calloc(tokens * width, sizeof(float));
    pocket->attended = calloc(tokens * width, sizeof(float));
    pocket->mixed = calloc(SCRATCH > tokens * 3 * width ? SCRATCH : tokens * 3 * width, 4);
    pocket->hidden = calloc(SCRATCH > tokens * 4 * width ? SCRATCH : tokens * 4 * width, 4);
    pocket->buffer = calloc(SCRATCH, sizeof(float));
    pocket->left = calloc(SCRATCH, sizeof(float));
    pocket->right = calloc(SCRATCH, sizeof(float));
    pocket->quants = calloc(tokens * 4 * width / QBLOCK, sizeof(Quant));
}

Pocket *pocket_open(const char *path, int threads) {
    int descriptor = open(path, O_RDONLY);
    if (descriptor < 0) return NULL;
    struct stat status;
    fstat(descriptor, &status);
    Pocket *pocket = calloc(1, sizeof(Pocket));
    pocket->file_size = (size_t)status.st_size;
    pocket->file = mmap(NULL, pocket->file_size, PROT_READ, MAP_PRIVATE, descriptor, 0);
    close(descriptor);
    threads = threads < 1 ? 1 : threads > MAX_THREADS ? MAX_THREADS : threads;
    pocket->threads = threads;
    if (pocket->file == MAP_FAILED || !parse(pocket) || !load_flow(pocket) || !load_mimi(pocket)) {
        pocket_close(pocket);
        return NULL;
    }
    allocate(pocket);
    start_pool(&pocket->pool, threads);
    load_time(pocket);
    return pocket;
}

static void free_stack(Stack *stack) {
    for (int index = 0; index < stack->count; index++) {
        Layer *layer = &stack->layers[index];
        free(layer->in_proj.dense);
        free(layer->out_proj.dense);
        free(layer->up.dense);
        free(layer->down.dense);
    }
    free(stack->keys);
    free(stack->values);
}

static void free_conv(Conv *conv) {
    free(conv->weight);
    free(conv->previous);
}

void pocket_close(Pocket *pocket) {
    if (!pocket) return;
    if (pocket->pool.count > 0) stop_pool(&pocket->pool);
    free_stack(&pocket->flow);
    free_stack(&pocket->mimi);
    Linear *linears[] = {&pocket->input_linear,    &pocket->out_eos,
                         &pocket->cond_embed,      &pocket->input_proj,
                         &pocket->final_modulation, &pocket->final_linear,
                         &pocket->quantizer};
    for (size_t index = 0; index < sizeof linears / sizeof *linears; index++) {
        free(linears[index]->dense);
    }
    for (int index = 0; index < pocket->block_count; index++) {
        free(pocket->blocks[index].first.dense);
        free(pocket->blocks[index].second.dense);
        free(pocket->blocks[index].modulation.dense);
    }
    free_conv(&pocket->head);
    free_conv(&pocket->tail);
    for (int stage = 0; stage < 3; stage++) {
        free(pocket->ups[stage].weight);
        free(pocket->ups[stage].partial);
        free_conv(&pocket->residuals[stage].first);
        free_conv(&pocket->residuals[stage].second);
    }
    float *buffers[] = {pocket->upsample, pocket->upsample_partial, pocket->x,
                        pocket->normed,   pocket->attended,         pocket->mixed,
                        pocket->hidden,   pocket->buffer,           pocket->left,
                        pocket->right};
    for (size_t index = 0; index < sizeof buffers / sizeof *buffers; index++) free(buffers[index]);
    free(pocket->quants);
    free(pocket->tensors);
    if (pocket->file && pocket->file != MAP_FAILED) munmap(pocket->file, pocket->file_size);
    free(pocket);
}

static int load_voice(Pocket *pocket, const char *voice, int extra) {
    Stack *flow = &pocket->flow;
    char format[160];
    snprintf(format, sizeof format, "voice.%s.%%d.offset", voice);
    const Tensor *offset = findf(pocket, format, 0);
    if (!offset) return 0;
    int length;
    memcpy(&length, offset->data, 4);
    reserve(flow, length + extra);
    for (int layer = 0; layer < flow->count; layer++) {
        snprintf(format, sizeof format, "voice.%s.%%d.cache", voice);
        const Tensor *cache = findf(pocket, format, layer);
        if (!cache || cache->type != TYPE_F16 || cache->shape[2] < length) return 0;
        size_t plane = (size_t)cache->shape[2] * flow->width;
        size_t at = (size_t)layer * flow->capacity * flow->width;
        for (size_t index = 0; index < (size_t)length * flow->width; index++) {
            flow->keys[at + index] = half(cache->data + index * 2);
            flow->values[at + index] = half(cache->data + (plane + index) * 2);
        }
    }
    flow->length = length;
    return 1;
}

static void reset_mimi(Pocket *pocket) {
    pocket->mimi.length = 0;
    memset(pocket->upsample_partial, 0, (size_t)UPSAMPLE * pocket->quantizer.rows * 4);
    Conv *convs[] = {&pocket->head,
                     &pocket->tail,
                     &pocket->residuals[0].first,
                     &pocket->residuals[0].second,
                     &pocket->residuals[1].first,
                     &pocket->residuals[1].second,
                     &pocket->residuals[2].first,
                     &pocket->residuals[2].second};
    for (size_t index = 0; index < sizeof convs / sizeof *convs; index++) {
        memset(convs[index]->previous, 0, (size_t)convs[index]->history * convs[index]->in * 4);
    }
    for (int stage = 0; stage < 3; stage++) {
        ConvT *conv = &pocket->ups[stage];
        memset(conv->partial, 0, (size_t)(conv->kernel - conv->stride) * conv->out * 4);
    }
    pocket->faded = 0;
}

static void embed(const Pocket *pocket, int32_t token, float *output) {
    int width = pocket->flow.width, blocks = width / QBLOCK;
    const uint8_t *row = pocket->embedding + (size_t)token * blocks * Q8_BYTES;
    for (int block = 0; block < blocks; block++, row += Q8_BYTES) {
        float scale = half(row);
        for (int index = 0; index < QBLOCK; index++) {
            output[block * QBLOCK + index] = scale * (float)(int8_t)row[2 + index];
        }
    }
}

int pocket_start(Pocket *pocket, const char *voice, const int32_t *tokens, int count,
                 int frames_after_eos, float temperature, uint64_t seed) {
    if (count < 1 || count > 512) return 0;
    int frames = (int)ceilf(((float)count / 3.0f + 2.0f) * 12.5f);
    if (!load_voice(pocket, voice, count + frames + 1)) return 0;
    for (int token = 0; token < count; token++) {
        embed(pocket, tokens[token], pocket->x + (size_t)token * pocket->flow.width);
    }
    forward(pocket, &pocket->flow, pocket->x, count);
    reset_mimi(pocket);
    pocket->first = 1;
    pocket->step = 0;
    pocket->eos_step = -1;
    pocket->after = frames_after_eos;
    pocket->max_frames = frames;
    pocket->done = 0;
    pocket->temperature = temperature;
    pocket->random = seed * 0x9E3779B97F4A7C15ull + 1;
    pocket->started = 1;
    return 1;
}

static float uniform(Pocket *pocket) {
    pocket->random ^= pocket->random >> 12;
    pocket->random ^= pocket->random << 25;
    pocket->random ^= pocket->random >> 27;
    uint64_t value = pocket->random * 0x2545F4914F6CDD1Dull;
    return ((float)(value >> 40) + 0.5f) / 16777216.0f;
}

static float gaussian(Pocket *pocket) {
    float radius = sqrtf(-2.0f * logf(uniform(pocket)));
    return radius * cosf(6.2831853f * uniform(pocket));
}

static void flow_head(Pocket *pocket, const float *condition, const float *noise, float *out) {
    float y[512], activated[512], x[512], normed[512], hidden[512], modulation[1536];
    apply(pocket, &pocket->cond_embed, condition, 1, y);
    for (int at = 0; at < 512; at++) {
        y[at] += pocket->time[at];
        activated[at] = silu(y[at]);
    }
    apply(pocket, &pocket->input_proj, noise, 1, x);
    for (int index = 0; index < pocket->block_count; index++) {
        const Block *block = &pocket->blocks[index];
        apply(pocket, &block->modulation, activated, 1, modulation);
        layer_norm(x, normed, 512, block->norm, block->norm_bias, 1e-6f);
        for (int at = 0; at < 512; at++) {
            normed[at] = normed[at] * (1 + modulation[512 + at]) + modulation[at];
        }
        apply(pocket, &block->first, normed, 1, hidden);
        for (int at = 0; at < 512; at++) hidden[at] = silu(hidden[at]);
        apply(pocket, &block->second, hidden, 1, normed);
        for (int at = 0; at < 512; at++) x[at] += modulation[1024 + at] * normed[at];
    }
    apply(pocket, &pocket->final_modulation, activated, 1, modulation);
    layer_norm(x, normed, 512, NULL, NULL, 1e-6f);
    for (int at = 0; at < 512; at++) {
        normed[at] = normed[at] * (1 + modulation[512 + at]) + modulation[at];
    }
    apply(pocket, &pocket->final_linear, normed, 1, out);
}

int pocket_next(Pocket *pocket, float *samples) {
    if (!pocket->started || pocket->done || pocket->step >= pocket->max_frames) return 0;
    const float *input = pocket->first ? pocket->bos : pocket->latent;
    float *x = pocket->x, condition[1024], eos, noise[LATENT], flow[LATENT];
    apply(pocket, &pocket->input_linear, input, 1, x);
    forward(pocket, &pocket->flow, x, 1);
    layer_norm(x, condition, pocket->flow.width, pocket->out_norm, pocket->out_norm_bias, 1e-5f);
    apply(pocket, &pocket->out_eos, condition, 1, &eos);
    int step = pocket->step;
    if (eos > -4.0f && pocket->eos_step < 0 && step >= MIN_FRAMES) pocket->eos_step = step;
    if (pocket->eos_step >= 0 && step >= pocket->eos_step + pocket->after) {
        pocket->done = 1;
        return 0;
    }
    float deviation = sqrtf(pocket->temperature);
    for (int at = 0; at < LATENT; at++) noise[at] = gaussian(pocket) * deviation;
    flow_head(pocket, condition, noise, flow);
    for (int at = 0; at < LATENT; at++) pocket->latent[at] = noise[at] + flow[at];
    pocket->first = 0;
    pocket->step++;
    decode(pocket, pocket->latent, samples);
    return POCKET_FRAME;
}
