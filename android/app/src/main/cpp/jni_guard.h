// Every JNI entry point runs inside JNI_TRY / JNI_CATCH: a C++ exception (std::bad_alloc
// for a frame too large for this phone's memory, a std::length_error, ...) must not cross
// into the VM, where it aborts the whole app. It becomes a Java exception instead, which
// the Kotlin side catches and reports ("冲洗失败：…") while the app keeps running.
#pragma once
#include <exception>
#include <jni.h>
#include <new>
#include <string>

inline void jni_rethrow(JNIEnv *env)
{
  if(env->ExceptionCheck()) return; // a Java exception is already on its way
  const char *type = "java/lang/IllegalStateException";
  std::string message = "native error";
  try
  {
    throw;
  }
  catch(const std::bad_alloc &)
  {
    type = "java/lang/OutOfMemoryError";
    message = "native: out of memory";
  }
  catch(const std::exception &e)
  {
    message = std::string("native: ") + e.what();
  }
  catch(...)
  {
  }
  jclass cls = env->FindClass(type);
  if(cls) env->ThrowNew(cls, message.c_str());
}

#define JNI_TRY try {
#define JNI_CATCH(env) } catch(...) { jni_rethrow(env); }
