#pragma once

#include <stdint.h>

typedef struct Pocket Pocket;

enum { POCKET_FRAME = 1920, POCKET_RATE = 24000 };

Pocket *pocket_open(const char *path, int threads);
void pocket_close(Pocket *pocket);
int pocket_start(Pocket *pocket, const char *voice, const int32_t *tokens, int count,
                 int frames_after_eos, float temperature, uint64_t seed);
int pocket_next(Pocket *pocket, float *samples);
