package org.agentos.lab;

import java.io.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/** Shared screen handles, input connections, and device phone-display access. */
final class PhoneExecutionScreens {
    static final String BASE="/data/local/agentos/virtual-display";
    interface Host {
        JSONObject start(String key)throws Exception;
        JSONObject status(String key)throws Exception;
        void stop(String key,String generation)throws Exception;
        default void stopRemoval(String key, String generation) throws Exception { stop(key, generation); }
        default int currentUser()throws Exception{return 0;}
        /** Removal recovery must distinguish an absent host from a failed status query. */
        default String removalState(String key, String generation) throws Exception {
            return generation.equals(status(key).getString("generation")) ? "current" : "replaced";
        }
    }
    interface Action { JSONObject run(Screen screen)throws Exception; }
    static final class Admission {
        final String thread,key,generation;final long revision;
        Admission(String thread, String key, String generation, long revision) {
            this.thread = thread;
            this.key = key;
            this.generation = generation;
            this.revision = revision;
        }
    }

    /** A session's screen as user control actions (take over, pause, give back) see it. */
    interface Lease {
        /** Held for a whole control action, so two never interleave. */
        ReentrantLock controlLock();

        /** Held while a step works on the screen. */
        ReentrantLock inputLock();

        Screen screenHandle();
    }

    /** Screen control and execution admission, also used by host tests. */
    interface Ledger {
        Lease exact(String thread, String key, String generation) throws Exception;

        JSONObject describe(String thread) throws Exception;

        void validate(Lease lease) throws Exception;

        void beginPause(String thread, String key, String generation) throws Exception;

        JSONObject finishControl(String thread, String key, String generation, String owner)
                throws Exception;

        void controlFailed(String thread, String key, String generation, String error)
                throws Exception;

        JSONObject userInput(
                String thread, String key, String generation, long controlRevision, Action action)
                throws Exception;

        /**
         * A sub-agent's screen was given back: its next turn, which the broker does not start, may
         * run.
         */
        void resumeGranted(String thread, String key, String generation) throws Exception;

        boolean agentAdmitted(String thread) throws Exception;

        Admission admission(String thread) throws Exception;

        boolean admissionCurrent(Admission grant) throws Exception;

        boolean turnStarted(String thread, Admission grant) throws Exception;
    }
    interface LiveConnection extends AutoCloseable {
        JSONObject status()throws Exception;
        int peerPid();
        int peerUid();
    }
    /** A gesture reuses one verified connection; an uncertain connection is never reopened. */
    static final class InputLease implements AutoCloseable {
        final Screen screen; final LiveConnection connection; private boolean closed;
        InputLease(Screen screen,LiveConnection connection){this.screen=screen;this.connection=connection;}
        boolean current(){
            if(closed)return false;
            try{screen.validateLive(connection.status(),connection.peerPid(),connection.peerUid());return true;}
            catch(Exception unavailable){try{close();}catch(Exception ignored){}return false;}
        }
        public void close()throws Exception{if(!closed){closed=true;connection.close();}}
    }
    static final class Screen {
        final String screenKey,generation,packageName; final int displayId,userId; final boolean legacy;
        Screen(String key,String generation,int id,boolean legacy){this(key,generation,id,legacy,"");}
        Screen(String key,String generation,int id,boolean legacy,String packageName){this(key,generation,id,legacy,packageName,0);}
        Screen(String key,String generation,int id,boolean legacy,String packageName,int userId){this.screenKey=key;this.generation=generation;this.displayId=id;this.legacy=legacy;this.packageName=packageName;this.userId=userId;}
        File directory(){return new File(legacy?BASE:BASE+"/screens/"+screenKey);}
        JSONObject json()throws Exception{return new JSONObject().put("screenKey",screenKey).put("generation",generation).put("displayId",displayId).put("userId",userId).put("packageName",packageName).put("stateDirectory",directory().getPath()).put("mirrorSocket",legacy?"taku-mirror":"taku-mirror-"+screenKey);}
        String command(String verb)throws Exception {
            if(legacy)return TaskBroker.command("/system_ext/bin/phone-display",verb);
            if(verb.equals("capture"))return TaskBroker.command("/system_ext/bin/phone-display",verb,screenKey,generation);
            return TaskBroker.command("/system_ext/bin/phone-display",verb,screenKey);
        }
        void validateCapture(JSONObject info)throws Exception {
            if(info.getInt("displayId")!=displayId || (!legacy && (!screenKey.equals(info.optString("screenKey"))||!generation.equals(info.optString("generation")))))
                throw new IOException("SCREEN_GENERATION_CHANGED");
            // Hosts refuse these themselves; a capture still reporting one is never shown as a page.
            String state=info.optString("displayState","ON");
            if(OFF_STATES.contains(state))throw captureFailure(new IOException("SCREEN_OFF: display state "+state));
        }
        void validateLive(JSONObject info,int peerPid,int peerUid)throws Exception {
            String socket=legacy?"taku-mirror":"taku-mirror-"+screenKey;
            String name=legacy?"Taku-Independent-Screen":"Taku-Independent-Screen-"+screenKey;
            if(peerPid<=0||(peerUid!=0&&peerUid!=1000&&peerUid!=2000)
                ||info.getInt("pid")!=peerPid||info.getInt("uid")!=peerUid
                ||info.getInt("displayId")!=displayId||!screenKey.equals(info.getString("screenKey"))
                ||generation.isEmpty()||!generation.equals(info.getString("generation"))
                ||!socket.equals(info.getString("mirrorSocket"))||!name.equals(info.getString("name"))
                ||!directory().getPath().equals(info.getString("stateDirectory")))
                throw new IOException("SCREEN_GENERATION_CHANGED");
        }
    }
    /** phone-display on the device. */
    static final Host DEVICE_HOST = new Host() {
        public JSONObject start(String key)throws Exception{return new JSONObject(TaskBroker.command("/system_ext/bin/phone-display","start",key));}
        public JSONObject status(String key)throws Exception{return new JSONObject(TaskBroker.command("/system_ext/bin/phone-display",CleanupWork.active()?"removal-read":"status",key));}
        public void stop(String key,String generation)throws Exception{TaskBroker.command("/system_ext/bin/phone-display","stop",key,generation);}
        public void stopRemoval(String key,String generation)throws Exception{TaskBroker.command("/system_ext/bin/phone-display","removal-stop",key,generation);}
        public String removalState(String key,String generation)throws Exception{return new JSONObject(TaskBroker.command("/system_ext/bin/phone-display","removal-status",key,generation)).getString("state");}
        public int currentUser()throws Exception{return Integer.parseInt(TaskBroker.command("/system/bin/am","get-current-user").trim());}
    };
    static final java.util.Set<String> OFF_STATES=java.util.Set.of("OFF","DOZE","DOZE_SUSPEND","ON_SUSPEND");
    static final String SCREEN_OFF_MESSAGE="SCREEN_OFF: This task's background screen is switched off (display not ON, e.g. after the power key), so no screenshot was taken. The App page is not blank. Retry observe_background in a few seconds; if it stays off, tell the user the background screen is off instead of giving up on the task.";
    static final String SCREEN_BLACK_FRAME_MESSAGE="SCREEN_BLACK_FRAME: Every pixel of the background screen's latest frame is pure black, so no screenshot was returned: nothing is drawn there yet, which is not an App page. If no App has been opened on this screen, call open_app; otherwise retry observe_background in a few seconds.";
    /** A capture the host refused as off/black, as one clear retryable Agent error; others unchanged. */
    static IOException captureFailure(IOException failure){
        String detail=failure.getMessage()==null?"":failure.getMessage();
        if(detail.contains("SCREEN_OFF"))return new IOException(SCREEN_OFF_MESSAGE,failure);
        if(detail.contains("SCREEN_BLACK_FRAME"))return new IOException(SCREEN_BLACK_FRAME_MESSAGE,failure);
        return failure;
    }
    static void thread(String id)throws IOException{if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("INVALID_SCREEN_THREAD");}
    static void packageName(String pkg)throws IOException{if(pkg==null||!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))throw new IOException("INVALID_SCREEN_PACKAGE");}
    static Screen descriptor(String key,JSONObject info,boolean legacy)throws Exception {
        int id=info.getInt("displayId");String generation=info.optString("generation");
        if(id<=0||(!legacy&&(!key.equals(info.optString("screenKey"))||!generation.matches("[a-f0-9-]{36}"))))throw new IOException("INVALID_SCREEN_HOST");
        return new Screen(key,generation,id,legacy);
    }
    static boolean current(Screen screen)throws Exception {
        if(DEVICE_HOST.currentUser()!=screen.userId)return false;
        Screen actual=descriptor(screen.screenKey,DEVICE_HOST.status(screen.screenKey),screen.legacy);
        return actual.displayId==screen.displayId&&actual.generation.equals(screen.generation);
    }
}
