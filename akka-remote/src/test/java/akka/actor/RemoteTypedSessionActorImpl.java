package akka.actor.remote;

import akka.actor.*;

import java.util.Set;
import java.util.HashSet;

import java.util.concurrent.CountDownLatch;

public class RemoteTypedSessionActorImpl extends TypedActor implements RemoteTypedSessionActor {


    private static Set<RemoteTypedSessionActor> instantiatedSessionActors = new HashSet<RemoteTypedSessionActor>();

    public static Set<RemoteTypedSessionActor> getInstances() {
        return instantiatedSessionActors;
    }

    @Override
    public void preStart() {
      instantiatedSessionActors.add(this);
    }

    @Override
    public void postStop()  {
      instantiatedSessionActors.remove(this);
    }

    
    private String user="anonymous";

    @Override
    public void login(String user) {
        this.user = user;
    }

    @Override
    public String getUser()
    {
        return this.user;
    }

    @Override
    public void doSomethingFunny() throws Exception
    {
        throw new Exception("Bad boy");
    }

}
