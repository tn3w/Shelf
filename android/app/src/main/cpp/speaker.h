#pragma once

#include <stdint.h>

typedef struct Speaker Speaker;

Speaker *speaker_open(const char *path, int context, int threads);
void speaker_close(Speaker *speaker);
int speaker_vocabulary(const Speaker *speaker);
int speaker_evaluate(Speaker *speaker, const int32_t *tokens, int count, int position);
const float *speaker_logits(Speaker *speaker, const int32_t *allowed, int allowed_count);
