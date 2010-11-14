package akka.transactor.test;

import akka.actor.UntypedActor;

public class UntypedFailer extends UntypedActor {
    public void onReceive(Object incoming) throws Exception {
        throw new RuntimeException("Expected failure");
    }
}
