package org.agentos.lab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class PhoneExecutionScreensTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    interface Checked {void run()throws Exception;}
    static void rejects(String code,Checked action)throws Exception {try{action.run();throw new AssertionError("Expected "+code);}catch(IOException error){check(error.getMessage().contains(code),"Unexpected rejection: "+error);}}
    static String thread(int value){return String.format("00000000-0000-4000-8000-%012d",value);}
    static String pkg(int value){return "test.app"+value;}
    static JSONObject okay(){return new JSONObject().put("ok",true);}
    static void pointers()throws Exception {
        AtomicLong now=new AtomicLong(100);List<String> events=new ArrayList<>();Map<Integer,String> generations=new HashMap<>();
        PhoneExecutionScreensPointers pointers=new PhoneExecutionScreensPointers(now::get,(display,down,at,phase,x,y,pressure)->events.add(display+":"+phase),s->s.generation.equals(generations.get(s.displayId)));
        PhoneExecutionScreens.Screen a=new PhoneExecutionScreens.Screen("a","old",2,false),b=new PhoneExecutionScreens.Screen("b","other",3,false);
        generations.put(2,"old");generations.put(3,"other");pointers.prepare(a);pointers.prepare(b);
        String one=thread(1)+"\n"+thread(11),two=thread(2)+"\n"+thread(22);pointers.startOwner(one);pointers.startOwner(two);
        pointers.pointer(2).touch(one,2,"DOWN",100,100);pointers.pointer(3).touch(two,3,"DOWN",200,200);
        pointers.endOwner(one);check(pointers.pointer(2).state()==null,"ending owner releases its gesture");check(pointers.pointer(3).state()!=null,"ending one owner preserves other display hold");
        check(events.equals(List.of("2:DOWN","3:DOWN","2:CANCEL")),"cancel injection addresses only owner display");
        now.set(16000);pointers.pointer(3).expire();check(events.get(events.size()-1).equals("3:CANCEL"),"other display timeout remains independent");
        pointers.startOwner(one+"x");pointers.pointer(2).touch(one+"x",2,"DOWN",100,100);int before=events.size();
        generations.put(2,"replacement");pointers.prepare(new PhoneExecutionScreens.Screen("new-key","replacement",2,false));
        check(events.size()==before,"stale cancellation never reaches recycled display id");
        check(pointers.pointer(2).state()==null,"recycled display has independent empty pointer");
        pointers.close();
    }
    static void midGestureReplacement()throws Exception {
        List<String> events=new ArrayList<>();AtomicReference<String> generation=new AtomicReference<>("original");
        PhoneExecutionScreensPointers pointers=new PhoneExecutionScreensPointers(()->100L,
            (display,down,at,phase,x,y,pressure)->events.add(phase),s->s.generation.equals(generation.get()));
        PhoneExecutionScreens.Screen original=new PhoneExecutionScreens.Screen("task-a","original",2,false);
        pointers.prepare(original);pointers.startOwner("turn");
        PhoneGestureSession pointer=pointers.pointer(2);pointer.touch("turn",2,"DOWN",100,100);
        generation.set("replacement");
        rejects("SCREEN_GENERATION_CHANGED",()->pointer.touch("turn",2,"MOVE",110,110));
        check(events.equals(List.of("DOWN")),"replacement during live gesture receives neither MOVE nor cleanup CANCEL");
        check(pointer.state()==null,"invalidated pointer cannot retain a reusable gesture");
        rejects("SCREEN_GESTURE_NOT_PREPARED",()->pointers.screen(2));
        rejects("no valid held gesture",()->pointer.touch("turn",2,"UP",110,110));
        pointers.prepare(new PhoneExecutionScreens.Screen("task-b","replacement",2,false));
        pointers.pointer(2).touch("turn",2,"DOWN",120,120);pointers.pointer(2).touch("turn",2,"UP",120,120);
        check(events.equals(List.of("DOWN","DOWN","UP")),"explicitly prepared replacement accepts only its new gesture");
        pointers.close();

        AtomicBoolean unavailable=new AtomicBoolean();List<String> disconnectedEvents=new ArrayList<>();
        PhoneExecutionScreensPointers disconnected=new PhoneExecutionScreensPointers(()->100L,
            (display,down,at,phase,x,y,pressure)->disconnectedEvents.add(phase),s->{if(unavailable.get())throw new IOException("socket closed");return true;});
        disconnected.prepare(original);disconnected.startOwner("turn");
        disconnected.pointer(2).touch("turn",2,"DOWN",100,100);unavailable.set(true);
        rejects("SCREEN_GENERATION_UNVERIFIED",()->disconnected.pointer(2).touch("turn",2,"UP",100,100));
        check(disconnectedEvents.equals(List.of("DOWN")),"lost status connection fails closed without UP or CANCEL");
        disconnected.close();
    }
    static void liveIdentity()throws Exception {
        String generation=UUID.randomUUID().toString();
        for(boolean legacy:new boolean[]{false,true}){
            String key=legacy?"default":"task-test";
            PhoneExecutionScreens.Screen screen=new PhoneExecutionScreens.Screen(key,generation,2,legacy);
            JSONObject status=screen.json().put("pid",1234).put("uid",0)
                .put("name",legacy?"Taku-Independent-Screen":"Taku-Independent-Screen-task-test");
            screen.validateLive(status,1234,0);
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("generation",UUID.randomUUID().toString()),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("displayId",3),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("screenKey","other"),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("mirrorSocket","other"),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("stateDirectory","/other"),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("name","other"),1234,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(status,4321,0));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(status,1234,2000));
            rejects("SCREEN_GENERATION_CHANGED",()->screen.validateLive(new JSONObject(status.toString()).put("uid",10001),1234,10001));
        }
    }
    static void inputLease()throws Exception {
        PhoneExecutionScreens.Screen screen=new PhoneExecutionScreens.Screen("task-test",UUID.randomUUID().toString(),2,false);
        class Connection implements PhoneExecutionScreens.LiveConnection {
            JSONObject status=screen.json().put("pid",1234).put("uid",0).put("name","Taku-Independent-Screen-task-test");
            int reads,closes;boolean fail;
            Connection()throws Exception{}
            public JSONObject status()throws Exception{reads++;if(fail)throw new IOException("socket disconnected");return status;}
            public int peerPid(){return 1234;}
            public int peerUid(){return 0;}
            public void close(){closes++;}
        }
        Connection first=new Connection(),second=new Connection();
        PhoneExecutionScreens.InputLease lease=new PhoneExecutionScreens.InputLease(screen,first),other=new PhoneExecutionScreens.InputLease(screen,second);
        check(lease.current()&&lease.current()&&first.reads==2,"gesture phases reuse one connection while checking each status");
        first.status.put("generation",UUID.randomUUID().toString());
        check(!lease.current()&&first.closes==1,"changed generation closes the input lease");
        check(!lease.current()&&first.reads==3,"retired input lease never reconnects or reads again");
        lease.close();check(first.closes==1,"input connection closes exactly once");
        check(other.current()&&second.closes==0,"another gesture connection remains independent");
        second.fail=true;check(!other.current()&&second.closes==1,"transport failure closes rather than guessing the generation");
        Connection third=new Connection();try(PhoneExecutionScreens.InputLease clean=new PhoneExecutionScreens.InputLease(screen,third)){check(clean.current(),"normal input lease admitted");}
        check(third.closes==1,"normal completion closes the connection");
    }
    static void policy()throws Exception {
        Path directory=Files.createTempDirectory("workspace-policy-test");File file=directory.resolve("policy.json").toFile();
        WorkspacePolicy.Store original=WorkspacePolicy.store;WorkspacePolicy.store=new WorkspacePolicy.Store(file);
        try{
            WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens",3));
            rejects("INVALID_WORKSPACE_LIMIT",()->WorkspacePolicy.configure(new JSONObject().put("maxActiveScreens",11)));
            rejects("INVALID_BACKGROUND_SETTING",()->WorkspacePolicy.configure(new JSONObject().put("allowBackground","true")));
            rejects("DUPLICATE_APP_PACKAGE",()->WorkspacePolicy.configure(new JSONObject().put("blockedPackages",new JSONArray().put(pkg(1)).put(pkg(1)))));
            rejects("UNKNOWN_WORKSPACE_SETTING",()->WorkspacePolicy.configure(new JSONObject().put("shell","anything")));
            check(new WorkspacePolicy.Store(file).read().getInt("maxActiveScreens")==3,"settings survive fresh store instance");
            Files.writeString(file.toPath(),"{broken");
            boolean failed=false;try{WorkspacePolicy.isAppAllowed(pkg(2));}catch(Exception expected){failed=true;}
            check(failed,"corrupt policy is rejected");
        }finally{WorkspacePolicy.store=original;Files.deleteIfExists(file.toPath());Files.delete(directory);}
    }
    static void removalCancellationMustBeConfirmed()throws Exception {
        AtomicBoolean unavailable=new AtomicBoolean();List<String> events=new ArrayList<>();
        PhoneExecutionScreensPointers pointers=new PhoneExecutionScreensPointers(()->100L,
            (display,down,at,phase,x,y,pressure)->events.add(phase),screen->{if(unavailable.get())throw new IOException("transport unavailable");return true;});
        pointers.prepare(new PhoneExecutionScreens.Screen("task-removal","original",2,false));
        String owner=thread(1)+"\n"+thread(11);pointers.startOwner(owner);pointers.pointer(2).touch(owner,2,"DOWN",100,100);
        unavailable.set(true);
        try(CleanupWork scope=new CleanupWork(100)){
            rejects("SCREEN_GENERATION_UNVERIFIED",()->pointers.pointer(2).cancelThread(thread(1)));
            check(pointers.pointer(2).state()!=null,"unverified removal cancellation retains pointer ownership");
            unavailable.set(false);pointers.pointer(2).cancelThread(thread(1));
            check(pointers.pointer(2).state()==null&&events.get(events.size()-1).equals("CANCEL"),"retry performs actual pointer cancellation before confirming cleanup");
        }
    }
    public static void main(String[] args)throws Exception {pointers();midGestureReplacement();liveIdentity();inputLease();policy();removalCancellationMustBeConfirmed();System.out.println("PhoneExecutionScreensTest: "+checks+" checks passed");}
}

final class TaskBroker {
    static final File ROOT=new File("unused-test-runtime");
    static boolean failWrites;
    static String read(File file)throws Exception{return Files.readString(file.toPath());}
    static void write(File file,String value)throws Exception {if(failWrites)throw new IOException("SIMULATED_WRITE_FAILURE");Path temp=Path.of(file+".tmp");Files.writeString(temp,value);Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    static String command(String... arguments)throws Exception{throw new IOException("Unexpected host command in unit test");}
}
