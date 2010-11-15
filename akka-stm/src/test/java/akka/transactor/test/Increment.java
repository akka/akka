package akka.transactor.test;

import akka.actor.ActorRef;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Increment {
    private List<ActorRef> friends;
    private CountDownLatch latch;

    public Increment(List<ActorRef> friends, CountDownLatch latch) {
        this.friends = friends;
        this.latch = latch;
    }

    public List<ActorRef> getFriends() {
        return friends;
    }

    public CountDownLatch getLatch() {
        return latch;
    }
}