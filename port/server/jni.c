/* JNI side of io.github.adhamura.gitphindroid.server.NativeServer. */
#include <jni.h>
#include "port.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static JavaVM* jvm;
static jclass server_cls; /* global ref */
static jmethodID collect_mid;
static jmethodID event_mid;

/* NativeServer.collect(kind) from the acceptor thread (attached for the call). */
static char* jni_collect(const char* kind) {
    if(!jvm || !server_cls || !collect_mid) return NULL;
    JNIEnv* env = NULL;
    bool attached = false;
    if((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return NULL;
        attached = true;
    }
    char* out = NULL;
    jstring jk = (*env)->NewStringUTF(env, kind);
    jstring r = (jstring)(*env)->CallStaticObjectMethod(env, server_cls, collect_mid, jk);
    if((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    } else if(r) {
        const char* c = (*env)->GetStringUTFChars(env, r, NULL);
        if(c) {
            out = strdup(c);
            (*env)->ReleaseStringUTFChars(env, r, c);
        }
        (*env)->DeleteLocalRef(env, r);
    }
    if(jk) (*env)->DeleteLocalRef(env, jk);
    if(attached) (*jvm)->DetachCurrentThread(jvm);
    return out;
}

/* NativeServer.onEvent(json) from the worker thread (attached for the call). Java only queues it. */
static void jni_event(const char* json) {
    if(!jvm || !server_cls || !event_mid) return;
    JNIEnv* env = NULL;
    bool attached = false;
    if((*jvm)->GetEnv(jvm, (void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if((*jvm)->AttachCurrentThread(jvm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    jstring js = (*env)->NewStringUTF(env, json); /* the event is ASCII only (port_webhook.c) */
    if(js) {
        (*env)->CallStaticVoidMethod(env, server_cls, event_mid, js);
        if((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, js);
    }
    if(attached) (*jvm)->DetachCurrentThread(jvm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    jvm = vm;
    JNIEnv* env;
    if((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "io/github/adhamura/gitphindroid/server/NativeServer");
    if(c) {
        server_cls = (*env)->NewGlobalRef(env, c);
        collect_mid = (*env)->GetStaticMethodID(env, c, "collect", "(Ljava/lang/String;)Ljava/lang/String;");
        if((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if(collect_mid) port_set_collector(jni_collect);
        event_mid = (*env)->GetStaticMethodID(env, c, "onEvent", "(Ljava/lang/String;)V");
        if((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if(event_mid) port_set_webhook_sink(jni_event);
    }
    return JNI_VERSION_1_6;
}

#define JFN(name) Java_io_github_adhamura_gitphindroid_server_NativeServer_##name

static char git_dir[1024]; /* nativeSetGitDir, used by the next nativeStart */

static const char* jstr(JNIEnv* env, jstring s) {
    return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static void jrel(JNIEnv* env, jstring s, const char* c) {
    if(s && c) (*env)->ReleaseStringUTFChars(env, s, c);
}

JNIEXPORT jint JNICALL JFN(nativeStart)(
    JNIEnv* env, jclass cls, jstring root, jstring bind, jint port, jint wifi, jstring user, jstring password) {
    (void)cls;
    const char* r = jstr(env, root);
    const char* b = jstr(env, bind);
    const char* u = jstr(env, user);
    const char* p = jstr(env, password);
    PortConfig cfg = {
        .root = r, .bind = b, .port = port, .wifi = wifi, .user = u, .password = p, .git_dir = git_dir};
    int rc = r ? port_server_start(&cfg) : -22;
    jrel(env, root, r);
    jrel(env, bind, b);
    jrel(env, user, u);
    jrel(env, password, p);
    return rc;
}

JNIEXPORT void JNICALL JFN(nativeStop)(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    port_server_stop();
}

JNIEXPORT jboolean JNICALL JFN(nativeIsRunning)(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    return port_server_running() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JFN(nativeStatus)(JNIEnv* env, jclass cls) {
    (void)cls;
    char buf[1024];
    port_server_status(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL JFN(nativeSetDevice)(JNIEnv* env, jclass cls, jstring name, jstring version, jint battery, jboolean charging) {
    (void)cls;
    const char* n = jstr(env, name);
    const char* v = jstr(env, version);
    port_set_device(n, v, battery, charging == JNI_TRUE);
    jrel(env, name, n);
    jrel(env, version, v);
}

JNIEXPORT void JNICALL JFN(nativeSetGitDir)(JNIEnv* env, jclass cls, jstring dir) {
    (void)cls;
    const char* d = jstr(env, dir);
    snprintf(git_dir, sizeof git_dir, "%s", d ? d : "");
    jrel(env, dir, d);
}
