/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import akka.actor.ActorRef;
import akka.transactor.UntypedTransactor;
import akka.transactor.SendTo;
import scala.concurrent.stm.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCounter extends UntypedTransactor {
    private String name;
    private Ref<Integer> count = Stm.ref(0);

    public UntypedCounter(String name) {
        this.name = name;
    }

    private void increment(InTxn txn) {
        Integer newValue = count.get(txn) + 1;
        count.set(newValue, txn);
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

    public void atomically(InTxn txn, Object message) {
        if (message instanceof Increment) {
            increment(txn);
            final Increment increment = (Increment) message;
            CompletionHandler countDown = new CompletionHandler() {
                public void handle(Txn.Status status) {
                    increment.getLatch().countDown();
                }
            };
            Stm.afterCompletion(countDown);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getSender().tell(count.single().get());
            return true;
        } else return false;
    }
}
