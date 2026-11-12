#include "pocket.h"
#include "speaker.h"

#define DECLARE(prefix)                                                                   \
    Speaker *prefix##speaker_open(const char *path, int context, int threads);            \
    void prefix##speaker_close(Speaker *speaker);                                         \
    int prefix##speaker_vocabulary(const Speaker *speaker);                               \
    int prefix##speaker_evaluate(Speaker *speaker, const int32_t *tokens, int count,      \
                                 int position);                                           \
    const float *prefix##speaker_logits(Speaker *speaker, const int32_t *allowed,         \
                                        int allowed_count);                                   \
    Pocket *prefix##pocket_open(const char *path, int threads);                            \
    void prefix##pocket_close(Pocket *pocket);                                            \
    int prefix##pocket_start(Pocket *pocket, const char *voice, const int32_t *tokens,     \
                             int count, int frames_after_eos, float temperature,          \
                             uint64_t seed);                                              \
    int prefix##pocket_next(Pocket *pocket, float *samples);

DECLARE(base_)

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>

DECLARE(dot_)

static int has_dotprod(void) {
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
}

#define PICK(name) (has_dotprod() ? dot_##name : base_##name)
#else
#define PICK(name) base_##name
#endif

Speaker *speaker_open(const char *path, int context, int threads) {
    return PICK(speaker_open)(path, context, threads);
}

void speaker_close(Speaker *speaker) {
    PICK(speaker_close)(speaker);
}

int speaker_vocabulary(const Speaker *speaker) {
    return PICK(speaker_vocabulary)(speaker);
}

int speaker_evaluate(Speaker *speaker, const int32_t *tokens, int count, int position) {
    return PICK(speaker_evaluate)(speaker, tokens, count, position);
}

const float *speaker_logits(Speaker *speaker, const int32_t *allowed, int allowed_count) {
    return PICK(speaker_logits)(speaker, allowed, allowed_count);
}

Pocket *pocket_open(const char *path, int threads) {
    return PICK(pocket_open)(path, threads);
}

void pocket_close(Pocket *pocket) {
    PICK(pocket_close)(pocket);
}

int pocket_start(Pocket *pocket, const char *voice, const int32_t *tokens, int count,
                 int frames_after_eos, float temperature, uint64_t seed) {
    return PICK(pocket_start)(pocket, voice, tokens, count, frames_after_eos, temperature, seed);
}

int pocket_next(Pocket *pocket, float *samples) {
    return PICK(pocket_next)(pocket, samples);
}
