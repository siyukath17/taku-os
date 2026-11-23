package android.util;

/** Host-JVM stand-in for android.util.Log (the SDK stub throws): records the last warning or error for assertions. */
public final class Log {
 public static String tag,msg;public static Throwable error;public static int level;
 public static int w(String t,String m,Throwable e){return record(5,t,m,e);}
 public static int e(String t,String m,Throwable e){return record(6,t,m,e);}
 static int record(int l,String t,String m,Throwable e){level=l;tag=t;msg=m;error=e;return 0;}
 public static void reset(){tag=null;msg=null;error=null;level=0;}
}
