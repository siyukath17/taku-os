package org.agentos.memory;

import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Host JVM checks for Recall's embedder fallback. A ROM build once shipped TakuMemory without the ONNX Runtime JNI
 * libraries: the first vector load threw UnsatisfiedLinkError, which `catch (Exception)` did not stop, and the memory
 * process crashed. Missing native code must degrade to keyword-only recall; fatal Errors must still propagate.
 */
public final class EmbedderFallbackHostTests {
 static int checks;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static void reset(){Recall.embedder=null;Recall.embedderError="";Recall.embedderUnlinkable=false;Log.reset();}

 public static void main(String[] args)throws Exception{
  // 1. The exact device failure, simulated: a missing JNI library degrades instead of crashing, and is logged as an error.
  reset();
  Embedder e=Recall.load(()->{throw new UnsatisfiedLinkError("dlopen failed: library \"libonnxruntime4j_jni.so\" not found");});
  check(e==null,"UnsatisfiedLinkError yields no embedder");
  check(Recall.embedderUnlinkable,"UnsatisfiedLinkError latches the unlinkable state");
  check(Recall.embedderError.contains("UnsatisfiedLinkError")&&Recall.embedderError.contains("libonnxruntime4j_jni.so"),"status keeps the link failure: "+Recall.embedderError);
  check(Log.level==6&&"TakuMemory".equals(Log.tag)&&Log.error instanceof UnsatisfiedLinkError,"link failure is logged as an error with its cause");

  // 2. Once unlinkable, embedder(Context) returns null without retrying the load (a null Context would throw if it did).
  Log.reset();
  check(Recall.embedder(null)==null,"embedder() short-circuits after a link failure");
  check(Log.level==0,"no repeated load or log after a link failure");

  // 3. The real ONNX Runtime classes with no native library on this host: first call UnsatisfiedLinkError, then
  //    NoClassDefFoundError from the failed class initializer. Both must degrade.
  for(int attempt=1;attempt<=2;attempt++){
   reset();
   e=Recall.load(()->new Embedder(new ByteArrayInputStream("[PAD]\n[UNK]\n".getBytes(StandardCharsets.UTF_8)),"/nonexistent/bge.onnx"));
   check(e==null,"real ONNX Runtime without native libraries yields no embedder (attempt "+attempt+")");
   check(Recall.embedderUnlinkable&&Log.error instanceof LinkageError,"real ONNX Runtime link failure is a LinkageError (attempt "+attempt+"): "+Log.error);
  }

  // 4. Ordinary failures (model missing, copy failed) degrade as before and stay retryable.
  reset();
  e=Recall.load(()->{throw new IOException("vector model missing");});
  check(e==null&&!Recall.embedderUnlinkable,"IOException degrades and stays retryable");
  check("vector model missing".equals(Recall.embedderError)&&Log.level==5,"IOException keeps its message and logs a warning");

  // 5. Fatal JVM errors are not swallowed.
  reset();
  boolean propagated=false;
  try{Recall.load(()->{throw new OutOfMemoryError("synthetic");});}catch(OutOfMemoryError expected){propagated=true;}
  check(propagated&&!Recall.embedderUnlinkable&&Log.level==0,"OutOfMemoryError propagates untouched");

  reset();
  System.out.println("EmbedderFallbackHostTests passed ("+checks+" checks)");
 }
}
