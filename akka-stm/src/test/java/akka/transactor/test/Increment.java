package akka.transactor.test;

import akka.actor.ActorRef;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Increment {
    List<ActorRef> friends;
    CountDownLatch latch;

    public Increment(List<ActorRef> friends, CountDownLatch latch) {
        this.friends = friends;
        this.latch = latch;
    }
}