/* Threads.c -- POSIX-реализация примитивов синхронизации LZMA SDK. */

#include "Threads.h"

#include <stdlib.h>

WRes Thread_Start(CThread *p, THREAD_FUNC_TYPE func, void *param) {
    if (p->created) {
        return SZ_ERROR_FAIL;
    }
    if (pthread_create(&p->thread, NULL, (void *(*)(void *)) func, param) != 0) {
        return SZ_ERROR_FAIL;
    }
    p->created = 1;
    return SZ_OK;
}

WRes Thread_Create(CThread *p, THREAD_FUNC_TYPE func, void *param) {
    return Thread_Start(p, func, param);
}

WRes Thread_Wait(CThread *p) {
    if (!p->created) {
        return SZ_OK;
    }
    pthread_join(p->thread, NULL);
    p->created = 0;
    return SZ_OK;
}

WRes ls_event_set(CEvent *p, int autoReset) {
    if (!p->created) {
        return SZ_ERROR_FAIL;
    }
    pthread_mutex_lock(&p->mutex);
    p->signaled = 1;
    pthread_cond_signal(&p->cond);
    pthread_mutex_unlock(&p->mutex);
    (void) autoReset;
    return SZ_OK;
}

WRes ls_event_reset(CEvent *p) {
    if (!p->created) {
        return SZ_ERROR_FAIL;
    }
    pthread_mutex_lock(&p->mutex);
    p->signaled = 0;
    pthread_mutex_unlock(&p->mutex);
    return SZ_OK;
}

WRes ls_event_wait(CEvent *p) {
    if (!p->created) {
        return SZ_ERROR_FAIL;
    }
    pthread_mutex_lock(&p->mutex);
    while (!p->signaled) {
        pthread_cond_wait(&p->cond, &p->mutex);
    }
    if (p->autoReset) {
        p->signaled = 0;
    }
    pthread_mutex_unlock(&p->mutex);
    return SZ_OK;
}

static WRes event_create(CEvent *p, int signaled, int autoReset) {
    if (pthread_mutex_init(&p->mutex, NULL) != 0) {
        return SZ_ERROR_FAIL;
    }
    if (pthread_cond_init(&p->cond, NULL) != 0) {
        pthread_mutex_destroy(&p->mutex);
        return SZ_ERROR_FAIL;
    }
    p->signaled = signaled;
    p->autoReset = autoReset;
    p->created = 1;
    return SZ_OK;
}

WRes ManualResetEvent_Create(CManualResetEvent *p, int signaled) {
    return event_create(p, signaled, 0);
}

WRes ManualResetEvent_CreateNotSignaled(CManualResetEvent *p) {
    return event_create(p, 0, 0);
}

WRes AutoResetEvent_Create(CAutoResetEvent *p, int signaled) {
    return event_create(p, signaled, 1);
}

WRes AutoResetEvent_CreateNotSignaled(CAutoResetEvent *p) {
    return event_create(p, 0, 1);
}

WRes Semaphore_Create(CSemaphore *p, UInt32 initCount, UInt32 maxCount) {
    if (pthread_mutex_init(&p->mutex, NULL) != 0) {
        return SZ_ERROR_FAIL;
    }
    if (pthread_cond_init(&p->cond, NULL) != 0) {
        pthread_mutex_destroy(&p->mutex);
        return SZ_ERROR_FAIL;
    }
    p->count = initCount;
    p->maxCount = maxCount;
    p->created = 1;
    return SZ_OK;
}

WRes ls_semaphore_wait(CSemaphore *p) {
    if (!p->created) {
        return SZ_ERROR_FAIL;
    }
    pthread_mutex_lock(&p->mutex);
    while (p->count == 0) {
        pthread_cond_wait(&p->cond, &p->mutex);
    }
    p->count--;
    pthread_mutex_unlock(&p->mutex);
    return SZ_OK;
}

WRes ls_semaphore_release(CSemaphore *p, UInt32 num) {
    if (!p->created) {
        return SZ_ERROR_FAIL;
    }
    pthread_mutex_lock(&p->mutex);
    p->count += num;
    pthread_cond_broadcast(&p->cond);
    pthread_mutex_unlock(&p->mutex);
    return SZ_OK;
}

WRes CriticalSection_Init(CCriticalSection *p) {
    if (pthread_mutex_init(p, NULL) != 0) {
        return SZ_ERROR_FAIL;
    }
    return SZ_OK;
}
