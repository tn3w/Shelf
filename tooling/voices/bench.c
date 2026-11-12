#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "speaker.h"

static double now(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec + time.tv_nsec / 1e9;
}

static int parse_tokens(const char *text, int32_t *tokens, int capacity) {
    int count = 0;
    for (char *end; *text && count < capacity; text = end) {
        tokens[count++] = (int32_t)strtol(text, &end, 10);
        while (*end == ',' || *end == ' ' || *end == '\n') end++;
        if (end == text) break;
    }
    return count;
}

static void print_resident(void) {
    char line[256];
    FILE *status = fopen("/proc/self/status", "r");
    while (status && fgets(line, sizeof line, status)) {
        if (strncmp(line, "VmRSS", 5) == 0) printf("after load %s", line);
    }
    if (status) fclose(status);
}

static int best(const float *logits, int size) {
    int chosen = 0;
    for (int index = 1; index < size; index++) {
        if (logits[index] > logits[chosen]) chosen = index;
    }
    return chosen;
}

int main(int count, char **arguments) {
    if (count < 5) {
        fprintf(stderr, "usage: bench MODEL TOKENS_FILE GENERATE THREADS\n");
        return 1;
    }
    static char text[1 << 20];
    FILE *file = fopen(arguments[2], "r");
    if (!file) return 1;
    text[fread(text, 1, sizeof text - 1, file)] = 0;
    fclose(file);
    static int32_t tokens[8192];
    int prompt = parse_tokens(text, tokens, 4096);
    int generate = atoi(arguments[3]);
    int context = prompt + 2 * generate + 8;
    Speaker *speaker = speaker_open(arguments[1], context, atoi(arguments[4]));
    if (!speaker) {
        fprintf(stderr, "cannot open model\n");
        return 1;
    }
    print_resident();
    double start = now();
    speaker_evaluate(speaker, tokens, prompt, 0);
    const float *logits = speaker_logits(speaker, NULL, 0);
    double prompt_seconds = now() - start;
    printf("prompt %d tokens: %.2f s, %.1f tokens/s\n", prompt, prompt_seconds,
           prompt / prompt_seconds);
    printf("generated:");
    start = now();
    for (int index = 0; index < generate; index++) {
        int32_t token = best(logits, speaker_vocabulary(speaker));
        printf(" %d", token);
        fflush(stdout);
        speaker_evaluate(speaker, &token, 1, prompt + index);
        logits = speaker_logits(speaker, NULL, 0);
    }
    double generate_seconds = now() - start;
    printf("\ngenerate %d tokens: %.2f s, %.1f tokens/s\n", generate, generate_seconds,
           generate / generate_seconds);
    int32_t allowed[] = {16, 17, 18, 19, 20};
    start = now();
    for (int index = 0; index < generate; index++) {
        int32_t token = allowed[index % 5];
        speaker_evaluate(speaker, &token, 1, prompt + generate + index);
        speaker_logits(speaker, allowed, 5);
    }
    double constrained_seconds = now() - start;
    printf("constrained generate %d tokens: %.1f tokens/s\n", generate,
           generate / constrained_seconds);
    speaker_close(speaker);
    return 0;
}
