#pragma once

#include <pthread.h>
#include <stdatomic.h>

enum { MAX_THREADS = 8, WORKER_SPIN = 20000, CALLER_SPIN = 200000 };

typedef void (*Job)(void *argument, int index, int count);

typedef struct Pool Pool;

typedef struct {
    Pool *pool;
    int index;
} Worker;

struct Pool {
    pthread_t threads[MAX_THREADS];
    Worker workers[MAX_THREADS];
    pthread_mutex_t lock;
    pthread_cond_t wake;
    pthread_cond_t done;
    int count;
    Job job;
    void *argument;
    atomic_int generation;
    atomic_int finished;
    atomic_int stopping;
    atomic_int sleepers;
};

static void wait_for(Pool *pool, pthread_cond_t *signal, atomic_int *value, int target,
                     int spins) {
    for (int spin = 0; spin < spins && atomic_load(value) < target; spin++) {
    }
    pthread_mutex_lock(&pool->lock);
    atomic_fetch_add(&pool->sleepers, 1);
    while (atomic_load(value) < target && !atomic_load(&pool->stopping)) {
        pthread_cond_wait(signal, &pool->lock);
    }
    atomic_fetch_sub(&pool->sleepers, 1);
    pthread_mutex_unlock(&pool->lock);
}

static void wake_sleepers(Pool *pool, pthread_cond_t *signal) {
    if (!atomic_load(&pool->sleepers)) return;
    pthread_mutex_lock(&pool->lock);
    pthread_cond_broadcast(signal);
    pthread_mutex_unlock(&pool->lock);
}

static void *work(void *argument) {
    Worker *worker = argument;
    Pool *pool = worker->pool;
    int seen = 0;
    for (;;) {
        wait_for(pool, &pool->wake, &pool->generation, seen + 1, WORKER_SPIN);
        if (atomic_load(&pool->stopping)) return NULL;
        seen = atomic_load(&pool->generation);
        pool->job(pool->argument, worker->index, pool->count + 1);
        if (atomic_fetch_add(&pool->finished, 1) + 1 == pool->count) {
            wake_sleepers(pool, &pool->done);
        }
    }
}

static void run(Pool *pool, Job job, void *argument) {
    pool->job = job;
    pool->argument = argument;
    atomic_store(&pool->finished, 0);
    atomic_fetch_add(&pool->generation, 1);
    wake_sleepers(pool, &pool->wake);
    job(argument, 0, pool->count + 1);
    wait_for(pool, &pool->done, &pool->finished, pool->count, CALLER_SPIN);
}

static void start_pool(Pool *pool, int threads) {
    pthread_mutex_init(&pool->lock, NULL);
    pthread_cond_init(&pool->wake, NULL);
    pthread_cond_init(&pool->done, NULL);
    pool->count = threads - 1;
    for (int index = 0; index < pool->count; index++) {
        pool->workers[index] = (Worker){pool, index + 1};
        pthread_create(&pool->threads[index], NULL, work, &pool->workers[index]);
    }
}

static void stop_pool(Pool *pool) {
    pthread_mutex_lock(&pool->lock);
    atomic_store(&pool->stopping, 1);
    pthread_cond_broadcast(&pool->wake);
    pthread_mutex_unlock(&pool->lock);
    for (int index = 0; index < pool->count; index++) pthread_join(pool->threads[index], NULL);
}
