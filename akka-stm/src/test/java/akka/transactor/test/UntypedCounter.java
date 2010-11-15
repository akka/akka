package akka.transactor.test;

import akka.transactor.UntypedTransactor;
import akka.transactor.SendTo;
import akka.actor.ActorRef;
import akka.stm.*;
import akka.util.Duration;

import org.multiverse.api.StmUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCounter extends UntypedTransactor {
    private String name;
    private Ref<Integer> count = new Ref<Integer>(0);

    public UntypedCounter(String name) {
        this.name = name;
    }

    @Override public TransactionFactory transactionFactory() {
        return new TransactionFactoryBuilder()
            .setTimeout(new Duration(3, TimeUnit.SECONDS))
            .build();
    }

    private void increment() {
        System.out.println(name + ": incrementing");
        count.set(count.get() + 1);
    }

    @Override public Set<SendTo> coordinate(Object message) {
        if (message instanceof Increment) {
            Increment increment = (Increment) message;
            List<ActorRef> friends = increment.getFriends();
            if (!friends.isEmpty()) {
                Increment coordMessage = new Increment(friends.subList(1, friends.size()), increment.getLatch());
                return include(friends.get(0), coordMessage);
            } else {
                return nobody();
            }
        } else {
            return nobody();
        }
    }

    @Override public void before(Object message) {
        System.out.println(name + ": before transaction");
    }

    public void atomically(Object message) {
        if (message instanceof Increment) {
            increment();
            final Increment increment = (Increment) message;
            StmUtils.scheduleDeferredTask(new Runnable() {
                public void run() { increment.getLatch().countDown(); }
            });
            StmUtils.scheduleCompensatingTask(new Runnable() {
                public void run() { increment.getLatch().countDown(); }
            });
        }
    }

    @Override public void after(Object message) {
        System.out.println(name + ": after transaction");
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getContext().replyUnsafe(count.get());
            return true;
        } else return false;
    }
}