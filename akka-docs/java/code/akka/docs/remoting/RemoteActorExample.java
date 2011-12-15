package akka.docs.remoting;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

public class RemoteActorExample extends UntypedActor {
    //#localNodeActor
    ActorRef a1 = getContext().actorFor("/serviceA/retrieval");
    //#localNodeActor

    //#remoteNodeActor
    ActorRef a2 = getContext().actorFor("akka://app@10.0.0.1:2552/user/theActor");
    //#remoteNodeActor

    public void onReceive(Object message) throws Exception {
        // Do something
    }
}
