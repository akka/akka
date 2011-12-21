/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import akka.actor.ActorRef;
import akka.actor.Actors;
import akka.actor.UntypedActor;
import scala.concurrent.stm.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCoordinatedCounter extends UntypedActor {
    private String name;
    private Ref<Integer> count = Stm.ref(0);

    public UntypedCoordinatedCounter(String name) {
        this.name = name;
    }

    private void increment(InTxn txn) {
        Integer newValue = count.get(txn) + 1;
        count.set(newValue, txn);
    }

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                List<ActorRef> friends = increment.getFriends();
                final CountDownLatch latch = increment.getLatch();
                final CompletionHandler countDown = new CompletionHandler() {
                    public void handle(Txn.Status status) {
                        latch.countDown();
                    }
                };
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).tell(coordinated.coordinate(coordMessage));
                }
                coordinated.atomic(new Atomically() {
                    public void atomically(InTxn txn) {
                        increment(txn);
                        Stm.afterCompletion(countDown);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.single().get());
        }
    }
}
