package akka.transactor.test;

import akka.transactor.Coordinated;
import akka.transactor.Atomically;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.stm.*;
import akka.util.Duration;

import org.multiverse.api.StmUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCoordinatedCounter extends UntypedActor {
    private String name;
    private Ref<Integer> count = new Ref(0);
    private TransactionFactory txFactory = new TransactionFactoryBuilder()
        .setTimeout(new Duration(3, TimeUnit.SECONDS))
        .build();

    public UntypedCoordinatedCounter(String name) {
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
                List<ActorRef> friends = increment.getFriends();
                final CountDownLatch latch = increment.getLatch();
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).sendOneWay(coordinated.coordinate(coordMessage));
                }
                coordinated.atomic(new Atomically(txFactory) {
                    public void atomically() {
                        increment();
                        StmUtils.scheduleDeferredTask(new Runnable() {
                            public void run() { latch.countDown(); }
                        });
                        StmUtils.scheduleCompensatingTask(new Runnable() {
                            public void run() { latch.countDown(); }
                        });
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
