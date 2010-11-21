package akka.transactor.example;

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
