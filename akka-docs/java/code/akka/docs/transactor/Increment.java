/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

//#class
import akka.actor.ActorRef;

public class Increment {
    private ActorRef friend = null;

    public Increment() {}

    public Increment(ActorRef friend) {
        this.friend = friend;
    }

    public boolean hasFriend() {
        return friend != null;
    }

    public ActorRef getFriend() {
        return friend;
    }
}
//#class
