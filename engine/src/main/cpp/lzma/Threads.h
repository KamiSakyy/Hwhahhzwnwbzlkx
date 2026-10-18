/* Threads.h -- POSIX-реализация примитивов синхронизации LZMA SDK.
 * Заменяет одноимённый Windows-файл из SDK (исходники SDK — public domain).
 * Используется только многопоточным матчером; мы всегда сжимаем в один поток.
 */
#ifndef __7Z_THREADS_H
#define __7Z_THREADS_H

#include <pthread.h>
#include <semaphore.h>

#include "7zTypes.h"

EXTERN_C_BEGIN

typedef struct {
    pthread_t thread;
    int created;
} CThread;

#define Thread_Construct(p) ((p)->created = 0)
#define Thread_WasCreated(p) ((p)->created != 0)
#define Thread_Close(p) ((void) 0)
#define Thread_Free(p) ((void) 0)

typedef unsigned THREAD_FUNC_RET_TYPE;
#define THREAD_FUNC_CALL_TYPE
#define THREAD_FUNC_DECL THREAD_FUNC_RET_TYPE THREAD_FUNC_CALL_TYPE
typedef THREAD_FUNC_RET_TYPE (THREAD_FUNC_CALL_TYPE * THREAD_FUNC_TYPE)(void *);

WRes Thread_Create(CThread *p, THREAD_FUNC_TYPE func, void *param);
WRes Thread_Start(CThread *p, THREAD_FUNC_TYPE func, void *param);
WRes Thread_Wait(CThread *p);

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int signaled;
    int autoReset;
    int created;
} CEvent;

typedef CEvent CAutoResetEvent;
typedef CEvent CManualResetEvent;

#define Event_Construct(p) ((p)->created = 0)
#define Event_IsCreated(p) ((p)->created != 0)
#define Event_Close(p) ((void) 0)
#define Event_Wait(p) ls_event_wait(p)
#define Event_Set(p) ls_event_set(p, 0)
#define Event_Reset(p) ls_event_reset(p)

WRes ls_event_wait(CEvent *p);
WRes ls_event_set(CEvent *p, int autoReset);
WRes ls_event_reset(CEvent *p);

WRes ManualResetEvent_Create(CManualResetEvent *p, int signaled);
WRes ManualResetEvent_CreateNotSignaled(CManualResetEvent *p);
WRes AutoResetEvent_Create(CAutoResetEvent *p, int signaled);
WRes AutoResetEvent_CreateNotSignaled(CAutoResetEvent *p);

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    unsigned count;
    unsigned maxCount;
    int created;
} CSemaphore;

#define Semaphore_Construct(p) ((p)->created = 0)
#define Semaphore_IsCreated(p) ((p)->created != 0)
#define Semaphore_Close(p) ((void) 0)
#define Semaphore_Wait(p) ls_semaphore_wait(p)
#define Semaphore_Release1(p) ls_semaphore_release(p, 1)
#define Semaphore_ReleaseN(p, n) ls_semaphore_release(p, (n))

WRes ls_semaphore_wait(CSemaphore *p);
WRes ls_semaphore_release(CSemaphore *p, UInt32 num);

WRes Semaphore_Create(CSemaphore *p, UInt32 initCount, UInt32 maxCount);

typedef pthread_mutex_t CCriticalSection;

WRes CriticalSection_Init(CCriticalSection *p);
#define CriticalSection_Delete(p) pthread_mutex_destroy(p)
#define CriticalSection_Enter(p) pthread_mutex_lock(p)
#define CriticalSection_Leave(p) pthread_mutex_unlock(p)

EXTERN_C_END

#endif
