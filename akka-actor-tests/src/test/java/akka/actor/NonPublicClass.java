/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

public class NonPublicClass {
    public static Props createProps() {
        return new Props(MyNonPublicActorClass.class);
    }
}

class MyNonPublicActorClass extends UntypedActor {
    @Override public void onReceive(Object msg) {
        getSender().tell(msg, getSelf());
    }
}