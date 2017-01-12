/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor;

public class NonPublicClass {
    public static Props createProps() {
        return Props.create(MyNonPublicActorClass.class);
    }
}

class MyNonPublicActorClass extends UntypedActor {
    @Override public void onReceive(Object msg) {
        getSender().tell(msg, getSelf());
    }
}
