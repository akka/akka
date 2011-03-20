package akka.serialization;

import akka.actor.UntypedActor;

public class SerializationTestActor extends UntypedActor {
    public void onReceive(Object msg) {
        getContext().replySafe("got it!");
    }
}
