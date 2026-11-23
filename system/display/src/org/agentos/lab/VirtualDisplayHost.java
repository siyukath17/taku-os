package org.agentos.lab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Owns a real independent Android display. The local socket exposes its actual frames and display-scoped input. */
public final class VirtualDisplayHost {
    static DisplaySlot slot;
    static File DIR;
    static String SOCKET;
    static final String generation=UUID.randomUUID().toString();
    static final int WIDTH=720,HEIGHT=1280,DENSITY=240;
    static volatile byte[] jpeg=new byte[0];
    static volatile long frameAt;
    static volatile long frameElapsedMs,frameSequence;
    static volatile boolean running=true;
    static int displayId;
    static JSONObject info;
    static LocalServerSocket server;
    static FileOutputStream owner;
    static FileLock ownerLock;
    static Handler mainHandler;
    static final Object captureLock=new Object();
    static volatile CaptureRequest pendingCapture;
    static final class CaptureRequest {
        final String id=UUID.randomUUID().toString();
        final CountDownLatch done=new CountDownLatch(1);
        volatile JSONObject result;
        volatile Exception error;
    }
    static final ExecutorService input=Executors.newSingleThreadExecutor();
    static int flag(String name) {
        try { Field f=DisplayManager.class.getDeclaredField(name); f.setAccessible(true); return f.getInt(null); }
        catch (Exception e) { return 0; }
    }
    static void write(File file, String text) throws Exception {
        File temp=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));}
        if(!temp.renameTo(file)) throw new IOException("rename failed: "+file);
    }
    static int coordinate(String value,int limit){return Math.max(0,Math.min(limit-1,Integer.parseInt(value)));}
    static JSONObject status()throws Exception {
        return new JSONObject(info.toString()).put("frameAt",frameAt).put("frameElapsedMs",frameElapsedMs)
            .put("frameSequence",frameSequence).put("transport","android-local-socket");
    }
    static void requireGeneration(String expected)throws IOException {
        DisplaySlot.requireSameGeneration(expected,generation);
        if(!running)throw new IOException("Screen is stopping");
    }
    static byte[] handle(String request)throws Exception {
        String[] p=request.trim().split(" +");
        if(p.length==1 && p[0].equals("frame"))return jpeg;
        if(p.length==1 && p[0].equals("status"))return status().toString().getBytes(StandardCharsets.UTF_8);
        if(p.length==2 && p[0].equals("capture")){
            requireGeneration(p[1]);
            synchronized(captureLock){
                requireGeneration(p[1]);
                CaptureRequest capture=new CaptureRequest();pendingCapture=capture;
                try{
                    if(!capture.done.await(5,TimeUnit.SECONDS))throw new IOException("Capture timed out waiting for a rendered frame");
                    if(capture.error!=null)throw capture.error;
                    return capture.result.toString().getBytes(StandardCharsets.UTF_8);
                }finally{if(pendingCapture==capture)pendingCapture=null;}
            }
        }
        if(p.length==2 && p[0].equals("stop")){
            requireGeneration(p[1]);
            // Flush the acknowledgement before the main looper closes this socket.
            mainHandler.postDelayed(()->{try{write(new File(DIR,"stop.request"),generation);}catch(Exception e){System.err.println("stop: "+e);}},200);
            return status().put("stopping",true).toString().getBytes(StandardCharsets.UTF_8);
        }
        String[] command;
        if(p.length==3 && p[0].equals("tap"))command=new String[]{"/system/bin/input","-d",Integer.toString(displayId),"tap",Integer.toString(coordinate(p[1],WIDTH)),Integer.toString(coordinate(p[2],HEIGHT))};
        else if(p.length==6 && p[0].equals("swipe"))command=new String[]{"/system/bin/input","-d",Integer.toString(displayId),"swipe",Integer.toString(coordinate(p[1],WIDTH)),Integer.toString(coordinate(p[2],HEIGHT)),Integer.toString(coordinate(p[3],WIDTH)),Integer.toString(coordinate(p[4],HEIGHT)),Integer.toString(Math.max(100,Math.min(1500,Integer.parseInt(p[5]))))};
        else if(p.length==2 && p[0].equals("key") && (p[1].equals("4")||p[1].equals("66")||p[1].equals("67")))command=new String[]{"/system/bin/input","-d",Integer.toString(displayId),"keyevent",p[1]};
        else if(p.length==2 && p[0].equals("launch") && p[1].equals("clock"))command=new String[]{"/system/bin/am","start","--display",Integer.toString(displayId),"-n","com.android.deskclock/.DeskClock"};
        else throw new IllegalArgumentException("unsupported mirror command");
        input.submit(()->{Process process=new ProcessBuilder(command).redirectErrorStream(true).start();try(InputStream in=process.getInputStream()){byte[] buffer=new byte[512];while(in.read(buffer)!=-1){}}if(process.waitFor()!=0)throw new IOException("display input failed");return null;}).get();
        return new JSONObject().put("ok",true).put("displayId",displayId).toString().getBytes(StandardCharsets.UTF_8);
    }
    static void startServer()throws Exception {
        server=new LocalServerSocket(SOCKET);
        Thread accept=new Thread(()->{
            while(running){try{LocalSocket client=server.accept();int uid=client.getPeerCredentials().getUid();
                if(uid!=0 && uid!=1000 && uid!=2000){client.close();continue;}
                Thread worker=new Thread(()->{try(LocalSocket socket=client){socket.setSoTimeout(10000);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));DataOutputStream out=new DataOutputStream(socket.getOutputStream());String request;while(running&&(request=reader.readLine())!=null){if(request.length()>256)throw new IOException("oversized command");byte[] result;try{result=handle(request);}catch(Exception e){result=("error: "+e.getMessage()).getBytes(StandardCharsets.UTF_8);}out.writeInt(result.length);out.write(result);out.flush();}}catch(Exception ignored){}},"mirror-client");worker.setDaemon(true);worker.start();
            }catch(Exception e){if(running)System.err.println("mirror socket: "+e);}}
        },"mirror-server");accept.setDaemon(true);accept.start();
    }
    public static void main(String[] args) throws Exception {
        slot=DisplaySlot.hostArguments(args);DIR=slot.directory;SOCKET=slot.socket;
        slot.checkDirectory();
        DIR.mkdirs();
        slot.checkDirectory();
        owner=new FileOutputStream(new File(DIR,"host.lock"));
        ownerLock=owner.getChannel().tryLock();
        if(ownerLock==null){System.err.println("Display host already owns the lock");System.exit(0);return;}
        // Only the new lock owner may clear stale requests from a previous run.
        new File(DIR,"stop.request").delete();new File(DIR,"capture.request").delete();
        new File(DIR,"display.id").delete();
        Looper.prepareMainLooper();
        Object thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context ctx=(Context)thread.getClass().getMethod("getSystemContext").invoke(thread);
        DisplayManager manager=(DisplayManager)ctx.getSystemService(Context.DISPLAY_SERVICE);
        Handler handler=new Handler(Looper.getMainLooper());mainHandler=handler;
        ImageReader reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,3);
        // Includes VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: the power key sleeping the default
        // display group must not turn this screen STATE_OFF under a running task (BUG-0034).
        int flags=ScreenGuard.virtualDisplayFlags(DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,VirtualDisplayHost::flag);
        VirtualDisplay vd=manager.createVirtualDisplay(slot.name,WIDTH,HEIGHT,DENSITY,reader.getSurface(),flags);
        if(vd==null)throw new IOException("createVirtualDisplay returned null");
        Display display=vd.getDisplay();displayId=display.getDisplayId();
        info=new JSONObject().put("displayId",displayId).put("name",display.getName()).put("width",WIDTH).put("height",HEIGHT).put("density",DENSITY).put("flags",flags).put("uid",android.os.Process.myUid()).put("pid",android.os.Process.myPid()).put("mirrorSocket",SOCKET)
            .put("screenKey",slot.key).put("generation",generation).put("stateDirectory",DIR.getPath());
        startServer();write(new File(DIR,"display.json"),info.toString(2));write(new File(DIR,"display.id"),Integer.toString(displayId));
        System.out.println(info.toString());System.out.flush();
        final Bitmap[] latest={null};final long[] saved={0},sequence={0};final boolean[] latestBlack={false};
        Runnable capture=()->{try{
            File request=new File(DIR,"capture.request");
            CaptureRequest pending=pendingCapture;
            if((pending==null && !request.exists()) || latest[0]==null)return;
            // An off/dozing screen renders nothing current and a pure-black frame is never a page:
            // fail with a retryable code rather than hand the Agent a black image (BUG-0034).
            int state=display.getState();
            try{ScreenGuard.requireScreenOn(state);ScreenGuard.requireNotBlack(latestBlack[0]);}
            catch(IOException refused){if(pending==null)request.delete();throw refused;}
            File temp=new File(DIR,"screen.png.tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){
                if(!latest[0].compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("PNG encoding failed");
            }
            if(!temp.renameTo(new File(DIR,"screen.png")))throw new IOException("PNG rename failed");
            JSONObject result=new JSONObject(info.toString())
                .put("capturedAt",System.currentTimeMillis()).put("frameAt",frameAt)
                .put("frameElapsedMs",saved[0]).put("frameAgeMs",android.os.SystemClock.elapsedRealtime()-saved[0])
                .put("frameSequence",sequence[0]).put("captureRequestId",pending==null?"legacy":pending.id)
                .put("displayState",ScreenGuard.stateName(state));
            write(new File(DIR,"capture.json"),result.toString());
            request.delete();
            if(pending!=null){pending.result=result;pending.done.countDown();if(pendingCapture==pending)pendingCapture=null;}
        }catch(Exception e){CaptureRequest pending=pendingCapture;if(pending!=null){pending.error=e;pending.done.countDown();if(pendingCapture==pending)pendingCapture=null;}System.err.println("capture: "+e);}};
        FrameCoalescer frames=new FrameCoalescer(android.os.SystemClock::uptimeMillis,new FrameCoalescer.Scheduler(){
            public void postDelayed(Runnable task,long delayMs){handler.postDelayed(task,delayMs);}
            public void remove(Runnable task){handler.removeCallbacks(task);}
        },200,()->{try(Image image=reader.acquireLatestImage()){
            if(image==null)return;
            long now=android.os.SystemClock.elapsedRealtime();
            Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();int paddedWidth=plane.getRowStride()/plane.getPixelStride();
            boolean black=ScreenGuard.isBlackRgba(buffer,WIDTH,HEIGHT,plane.getRowStride(),plane.getPixelStride());
            Bitmap padded=Bitmap.createBitmap(paddedWidth,HEIGHT,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(buffer);
            Bitmap frame=Bitmap.createBitmap(padded,0,0,WIDTH,HEIGHT);if(frame!=padded)padded.recycle();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!frame.compress(Bitmap.CompressFormat.JPEG,78,bytes)){frame.recycle();throw new IOException("JPEG encoding failed");}
            if(latest[0]!=null)latest[0].recycle();latest[0]=frame;latestBlack[0]=black;saved[0]=now;sequence[0]++;
            jpeg=bytes.toByteArray();frameAt=System.currentTimeMillis();frameElapsedMs=now;frameSequence=sequence[0];
            // A pending capture must see this final frame even if no later frame arrives.
            capture.run();
        }catch(Exception e){System.err.println("frame: "+e);}});
        // Do not acquire-and-discard inside the throttle window: the last UI frame
        // may be the only remaining notification after a short drag or animation.
        reader.setOnImageAvailableListener(r->frames.onFrameAvailable(),handler);
        handler.post(new Runnable(){public void run(){try{
            if(new File(DIR,"stop.request").exists()){
                running=false;
                frames.close();
                reader.setOnImageAvailableListener(null,null);
                try {
                    try { server.close(); } catch(IOException e) { System.err.println("socket close: "+e); }
                    input.shutdownNow();
                    try { vd.release(); } catch(RuntimeException e) { System.err.println("display release: "+e); }
                    try { reader.close(); } catch(RuntimeException e) { System.err.println("reader close: "+e); }
                    if(latest[0]!=null)latest[0].recycle();
                } finally {
                    new File(DIR,"stop.request").delete();
                    new File(DIR,"display.id").delete();
                    // prepareMainLooper creates a non-quittable queue. This standalone
                    // app_process must exit, releasing Binder/runtime threads and every fd.
                    System.exit(0);
                }
                return;
            }
            // Static screens need no new producer frame. If an update is queued,
            // its consumer above will export it instead of returning the old cache.
            if(!frames.hasPendingFrame())capture.run();
        }catch(Exception e){System.err.println("capture: "+e);}handler.postDelayed(this,200);}});
        Looper.loop();
    }
}
