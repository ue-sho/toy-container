#include <jni.h>
#include <sched.h>
#include <sys/wait.h>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_toycontainer_Container_createNamespacedProcess(JNIEnv* env, jclass clazz) {
    int flags = CLONE_NEWUTS | CLONE_NEWPID | CLONE_NEWNS;
    return flags;
}