package akka.transactor.test;

import akka.transactor.Coordinated;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.stm.*;
import akka.util.Duration;

import org.multiverse.api.StmUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CoordinatedCounter extends UntypedActor {
    String name;
    Ref<Integer> count = new Ref(0);
    TransactionFactory txFactory = new TransactionFactoryBuilder()
        .setTimeout(new Duration(3, TimeUnit.SECONDS))
        .build();

    public CoordinatedCounter(String name) {
        this.name = name;
    }

    private void increment() {
        System.out.println(name + ": incrementing");
        count.set(count.get() + 1);
    }

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                List<ActorRef> friends = increment.friends;
                final CountDownLatch latch = increment.latch;
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).sendOneWay(coordinated.coordinate(coordMessage));
                }
                coordinated.atomic(new Atomic<Object>(txFactory) {
                    public Object atomically() {
                        increment();
                        StmUtils.scheduleDeferredTask(new Runnable() {
                            public void run() { latch.countDown(); }
                        });
                        StmUtils.scheduleCompensatingTask(new Runnable() {
                            public void run() { latch.countDown(); }
                        });
                        return null;
                    }
                });
            }
        } else if (incoming instanceof String) {
            String message = (String) incoming;
            if (message.equals("GetCount")) {
                getContext().replyUnsafe(count.get());
            }
        }
    }
}
