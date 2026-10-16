#include <jni.h>
extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_forge_runtime_Key_read(JNIEnv *e,jclass c) {
 const unsigned char k[32]={31,81,9,72,111,23,94,6,37,88,121,45,16,97,54,2,91,66,35,117,8,76,49,103,19,56,124,41,85,12,69,28};
 jbyteArray a=e->NewByteArray(32); e->SetByteArrayRegion(a,0,32,(const jbyte*)k); return a;
}
