#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef __NR_prlimit64
#if defined(__aarch64__)
#define __NR_prlimit64 267
#endif
#endif

static int my_prlimit64(pid_t pid, int resource, const struct rlimit *new_limit, struct rlimit *old_limit) {
#ifdef __NR_prlimit64
    return syscall(__NR_prlimit64, pid, resource, new_limit, old_limit);
#else
    errno = ENOSYS;
    return -1;
#endif
}

#define TAG "TunguskaVpnNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_acionyx_tunguska_vpnservice_VpnNativeProcessBridge_nativeStartProcessWithFd(
        JNIEnv *env, jclass,
        jstring jCmd, jobjectArray jArgs, jobjectArray jEnvKeys, jobjectArray jEnvVals, jint keepFd, jint maxFds) {
    const char *cmd = env->GetStringUTFChars(jCmd, nullptr);

    int argc = env->GetArrayLength(jArgs);
    char **argv = static_cast<char **>(malloc((argc + 1) * sizeof(char *)));
    for (int i = 0; i < argc; i++) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
        argv[i] = const_cast<char *>(env->GetStringUTFChars(js, nullptr));
    }
    argv[argc] = nullptr;

    int envc = jEnvKeys ? env->GetArrayLength(jEnvKeys) : 0;
    char **envp = nullptr;
    if (envc > 0) {
        envp = static_cast<char **>(malloc((envc + 1) * sizeof(char *)));
        for (int i = 0; i < envc; i++) {
            jstring key = static_cast<jstring>(env->GetObjectArrayElement(jEnvKeys, i));
            jstring val = static_cast<jstring>(env->GetObjectArrayElement(jEnvVals, i));
            const char *k = env->GetStringUTFChars(key, nullptr);
            const char *v = env->GetStringUTFChars(val, nullptr);
            char *pair = static_cast<char *>(malloc(strlen(k) + strlen(v) + 2));
            sprintf(pair, "%s=%s", k, v);
            envp[i] = pair;
            env->ReleaseStringUTFChars(key, k);
            env->ReleaseStringUTFChars(val, v);
            env->DeleteLocalRef(key);
            env->DeleteLocalRef(val);
        }
        envp[envc] = nullptr;
    }

    if (keepFd >= 0) {
        int flags = fcntl(static_cast<int>(keepFd), F_GETFD);
        if (flags < 0) {
            LOGE("fcntl(F_GETFD) failed for fd %d: %s", static_cast<int>(keepFd), strerror(errno));
        } else if (fcntl(static_cast<int>(keepFd), F_SETFD, flags & ~FD_CLOEXEC) < 0) {
            LOGE("fcntl(F_SETFD) failed for fd %d: %s", static_cast<int>(keepFd), strerror(errno));
        } else {
            LOGI("Cleared FD_CLOEXEC on fd %d", static_cast<int>(keepFd));
        }
    }

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        LOGE("fork() failed: %s", strerror(err));
        env->ReleaseStringUTFChars(jCmd, cmd);
        for (int i = 0; i < argc; i++) {
            jstring js = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
            env->ReleaseStringUTFChars(js, argv[i]);
        }
        free(argv);
        return static_cast<jlong>(-err);
    }

    if (pid == 0) {
        DIR *dir = opendir("/proc/self/fd");
        if (dir != nullptr) {
            int dirFd = dirfd(dir);
            struct dirent *ent;
            while ((ent = readdir(dir)) != nullptr) {
                int fd = atoi(ent->d_name);
                if (fd > 2 && fd != dirFd) {
                    if (keepFd < 0 || fd != static_cast<int>(keepFd)) {
                        close(fd);
                    }
                }
            }
            closedir(dir);
        }

        struct rlimit newrl;
        newrl.rlim_cur = static_cast<rlim_t>(maxFds);
        newrl.rlim_max = static_cast<rlim_t>(maxFds);

        int rc = my_prlimit64(0, RLIMIT_NOFILE, &newrl, nullptr);
        if (rc != 0) {
            rc = setrlimit(RLIMIT_NOFILE, &newrl);
            if (rc != 0) {
                LOGE("Failed to raise RLIMIT_NOFILE in child, errno=%d (%s)", errno, strerror(errno));
            }
        }

        if (envp != nullptr) {
            execve(cmd, argv, envp);
        } else {
            execv(cmd, argv);
        }
        _exit(127);
    }

    LOGI("Forked child pid %d for %s", pid, cmd);
    env->ReleaseStringUTFChars(jCmd, cmd);
    for (int i = 0; i < argc; i++) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(jArgs, i));
        env->ReleaseStringUTFChars(js, argv[i]);
    }
    free(argv);

    if (envp != nullptr) {
        for (int i = 0; envp[i] != nullptr; i++) {
            free(envp[i]);
        }
        free(envp);
    }

    return static_cast<jlong>(pid);
}

JNIEXPORT jint JNICALL
Java_io_acionyx_tunguska_vpnservice_VpnNativeProcessBridge_nativeKillProcess(
        JNIEnv *, jclass, jlong pid) {
    if (pid <= 0) return -1;
    return kill(static_cast<pid_t>(pid), SIGKILL);
}

JNIEXPORT jint JNICALL
Java_io_acionyx_tunguska_vpnservice_VpnNativeProcessBridge_nativeSetMaxFds(
        JNIEnv *, jclass, jint maxFds) {
    struct rlimit cur;
    if (getrlimit(RLIMIT_NOFILE, &cur) != 0) return errno;

    struct rlimit newrl;
    newrl.rlim_cur = static_cast<rlim_t>(maxFds);
    newrl.rlim_max = static_cast<rlim_t>(maxFds);
    if (setrlimit(RLIMIT_NOFILE, &newrl) != 0) {
        newrl.rlim_cur = cur.rlim_max;
        newrl.rlim_max = cur.rlim_max;
        if (setrlimit(RLIMIT_NOFILE, &newrl) != 0) return errno;
    }
    return 0;
}

}
