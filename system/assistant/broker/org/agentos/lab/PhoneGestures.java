package org.agentos.lab;

import android.os.SystemClock;
import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One pointer stream per display, each owned by its native thread/turn. */
final class PhoneGestures {
    private static final ThreadLocal<GestureGuard> activeGuard=new ThreadLocal<>();
    private static final PhoneExecutionScreensPointers pointers=new PhoneExecutionScreensPointers(SystemClock::uptimeMillis,PhoneGestures::inject,PhoneGestures::current);
    private static boolean current(PhoneExecutionScreens.Screen screen)throws Exception {
        GestureGuard guard=activeGuard.get();
        if(guard!=null&&guard.screen==screen)return guard.current();
        return PhoneExecutionScreens.current(screen);
    }
    /** One read-only connection per gesture, reused for every phase and always closed. */
    private static final class GestureGuard implements AutoCloseable {
        final PhoneExecutionScreens.Screen screen;
        final LocalSocket socket=new LocalSocket();
        final PhoneExecutionScreens.InputLease lease;
        GestureGuard(PhoneExecutionScreens.Screen screen)throws Exception {
            this.screen=screen;
            try {
                socket.connect(new LocalSocketAddress(screen.legacy?"taku-mirror":"taku-mirror-"+screen.screenKey,LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(1000);
                Credentials peer=socket.getPeerCredentials();
                lease=new PhoneExecutionScreens.InputLease(screen,new PhoneExecutionScreens.LiveConnection(){
                    public int peerPid(){return peer.getPid();}
                    public int peerUid(){return peer.getUid();}
                    public JSONObject status()throws Exception{return readStatus();}
                    public void close()throws IOException{socket.close();}
                });
                if(!current())throw new IOException("SCREEN_GENERATION_CHANGED");
            }catch(Exception error){socket.close();throw error;}
        }
        JSONObject readStatus()throws Exception {
            OutputStream output=socket.getOutputStream();output.write("status\n".getBytes(StandardCharsets.UTF_8));output.flush();
            DataInputStream input=new DataInputStream(socket.getInputStream());int size=input.readInt();
            if(size<1||size>65536)throw new IOException("INVALID_SCREEN_STATUS");
            byte[] bytes=new byte[size];input.readFully(bytes);
            return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        }
        boolean current(){return lease.current();}
        public void close()throws Exception{lease.close();}
    }
    interface GuardedAction {JSONObject run()throws Exception;}
    private static JSONObject guarded(int display,GuardedAction action)throws Exception {
        try(GestureGuard guard=new GestureGuard(pointers.screen(display))){
            activeGuard.set(guard);
            try{return action.run();}finally{activeGuard.remove();}
        }
    }
    static void prepare(PhoneExecutionScreens.Screen screen)throws Exception {pointers.prepare(screen);}
    /**
     * The screen's process is up and is the one the screen names: one status read on its own socket,
     * no new process. Fails when the process is gone.
     */
    static void requireLive(PhoneExecutionScreens.Screen screen)throws Exception {
        try(GestureGuard guard=new GestureGuard(screen)){}
    }
    private static PhoneGestureSession pointer(int display)throws IOException {return pointers.pointer(display);}
    private static List<PhoneGestureSession> snapshot(){return pointers.snapshot();}
    static {
        Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"phone-touch-release");t.setDaemon(true);return t;})
            .scheduleWithFixedDelay(()->{for(PhoneGestureSession pointer:snapshot())try{pointer.expire();}catch(Exception e){android.util.Log.e("TakuPhoneTouch","Pointer release failed; ownership retained");}},250,250,TimeUnit.MILLISECONDS);
    }
    private static Object manager;
    private static Method inject,setDisplay;
    private static final java.util.concurrent.locks.ReentrantLock INPUT_INIT=new java.util.concurrent.locks.ReentrantLock();
    private static void initializeInput()throws Exception {try(CleanupWork.Held held=CleanupWork.lock(INPUT_INIT)){
        if(manager!=null)return;
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
    }}
    private static void inject(int display,long downAt,long at,String phase,int x,int y,float pressure) throws Exception {
        initializeInput();
        int action=switch(phase){case "DOWN"->MotionEvent.ACTION_DOWN;case "MOVE"->MotionEvent.ACTION_MOVE;case "UP"->MotionEvent.ACTION_UP;default->MotionEvent.ACTION_CANCEL;};
        MotionEvent event=MotionEvent.obtain(downAt,at,action,x,y,pressure,1f,0,1f,1f,0,0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);setDisplay.invoke(event,display);
            if(!Boolean.TRUE.equals(inject.invoke(manager,event,2)))throw new IOException("The system rejected the independent-screen touch");
        } finally { event.recycle(); }
    }
    static int integer(JSONObject args,String name) throws Exception {
        Object value=args.get(name);
        if(!(value instanceof Integer))throw new IOException("Gesture parameter must be an integer: "+name);
        return (Integer)value;
    }
    private static String owner(JSONObject call) throws Exception {
        return GestureOwners.of(call.optString("threadId"),call.optString("turnId"));
    }
    static JSONObject touch(JSONObject call,int display,JSONObject args) throws Exception {
        return guarded(display,()->{
            PhoneGestureSession pointer=pointer(display);
            pointer.touch(owner(call),display,args.getString("phase"),integer(args,"x"),integer(args,"y"));
            return state(display);
        });
    }
    static JSONObject swipe(JSONObject call,int display,JSONObject args) throws Exception {
        String owner=owner(call);int x=integer(args,"fromX"),y=integer(args,"fromY"),toX=integer(args,"toX"),toY=integer(args,"toY"),duration=integer(args,"durationMs");
        PhoneGestureSession.coordinates(x,y);PhoneGestureSession.coordinates(toX,toY);
        if(duration<100 || duration>5000)throw new IOException("Swipe duration must be between 100 and 5000 milliseconds");
        return guarded(display,()->{
         PhoneGestureSession pointer=pointer(display);pointer.touch(owner,display,"DOWN",x,y);
         try {
            long start=SystemClock.uptimeMillis();
            while(true) {
                long elapsed=SystemClock.uptimeMillis()-start;float fraction=Math.min(1f,(float)elapsed/duration);
                pointer.touch(owner,display,"MOVE",Math.round(x+(toX-x)*fraction),Math.round(y+(toY-y)*fraction));
                if(fraction>=1f)break;Thread.sleep(16);
            }
            pointer.touch(owner,display,"UP",toX,toY);
         } finally { pointer.cancelOwner(owner); }
         return state(display);
        });
    }
    static JSONObject state(int display) throws Exception {
        PhoneGestureSession.State state=pointer(display).state();
        JSONObject result=new JSONObject().put("held",state!=null).put("displayId",display).put("idleReleaseMs",PhoneGestureSession.HOLD_MS);
        if(state!=null)result.put("x",state.x()).put("y",state.y())
            .put("downTimeUptimeMs",state.downAt()).put("deadlineUptimeMs",state.expiresAt())
            .put("remainingMillis",Math.max(0,state.expiresAt()-SystemClock.uptimeMillis()));
        return result;
    }
    static void requireIdle(int display) throws Exception {pointer(display).requireIdle();}
    static void requireIdle() throws Exception {for(PhoneGestureSession pointer:snapshot())pointer.requireIdle();}
    static void startTurn(String thread,String turn) {pointers.startOwner(thread+"\n"+turn);}
    static void endTurn(String thread,String turn) {try{pointers.endOwner(thread+"\n"+turn);}catch(Exception e){android.util.Log.e("TakuPhoneTouch","Turn ended but pointer release failed; retrying on timeout");}}
    static void releaseTurn(String thread,String turn) {for(PhoneGestureSession pointer:snapshot())try{pointer.cancelOwner(thread+"\n"+turn);}catch(Exception e){android.util.Log.e("TakuPhoneTouch","Pointer release failed; retrying on timeout");}}
    static void cancelThread(String thread) {for(PhoneGestureSession pointer:snapshot())try{pointer.cancelThread(thread);}catch(Exception e){android.util.Log.e("TakuPhoneTouch","Turn ended but pointer release failed; retrying on timeout");}}
    static void cancelThreadChecked(String thread) throws Exception {
        Exception first=null;
        for(PhoneGestureSession pointer:snapshot())try{pointer.cancelThread(thread);}
        catch(Exception error){if(first==null)first=error;else first.addSuppressed(error);}
        if(first!=null)throw first;
    }
    static void cancelAll() {try{pointers.close();}catch(Exception e){android.util.Log.e("TakuPhoneTouch","Runtime ended but pointer release failed; retrying on timeout");}}
}
