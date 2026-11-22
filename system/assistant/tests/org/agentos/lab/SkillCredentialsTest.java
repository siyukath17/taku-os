package org.agentos.lab;

import android.system.Os;
import java.io.File;
import org.json.JSONObject;

/** Disposable AVD: synthetic token, no account or model access. */
public final class SkillCredentialsTest {
    static void check(boolean value,String message) throws Exception { if(!value)throw new Exception(message); }
    public static void main(String[] args) throws Exception {
        String repo="taku-test-nonexistent/private-fixture", secret="fixture-token-do-not-use-12345";
        JSONObject request=new JSONObject().put("repo",repo);
        SkillCredentials.control(new JSONObject(request.toString()).put("action","remove"));
        try {
            JSONObject source=SkillCommunity.source("fixture","fixture",repo,"").put("private",true);
            try { SkillCommunity.commit(source);throw new Exception("missing credential accepted"); }
            catch(java.io.IOException expected) { check(expected.getMessage().contains("No GitHub credentials are configured"),"unclear missing credential error"); }
            check(!SkillCredentials.control(new JSONObject(request.toString()).put("action","status")).getBoolean("configured"),"unexpected token");
            JSONObject saved=SkillCredentials.control(new JSONObject(request.toString()).put("action","set").put("token",secret));
            check(saved.getBoolean("configured")&&!saved.toString().contains(secret),"credential exposed in response");
            File file=new File("/data/local/agentos/private/github/"+HarnessStore.hash(repo)+".token");
            check((Os.stat(file.getPath()).st_mode & 0777)==0600,"credential file is not private");
            check((Os.stat(file.getParent()).st_mode & 0777)==0700,"credential directory is not private");
            check(secret.equals(SkillCredentials.get(repo)),"credential not retrieved");
            try { SkillCommunity.commit(source);throw new Exception("invalid token unexpectedly worked"); }
            catch(java.io.IOException expected) { check(expected.getMessage().startsWith("Cannot access the private repository"),"expected GitHub to reject the synthetic token"); check(!expected.toString().contains(secret),"credential leaked in error"); }
            System.out.println("PASS: missing credential, private device storage, redacted result/error and removal");
        } finally { SkillCredentials.control(new JSONObject(request.toString()).put("action","remove")); }
        check(!SkillCredentials.control(new JSONObject(request.toString()).put("action","status")).getBoolean("configured"),"credential not removed");
        System.exit(0);
    }
}
