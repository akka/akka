/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import akka.actor.ActorRef;
import akka.actor.Actors;
import akka.actor.UntypedActor;
import akka.util.FiniteDuration;
import scala.Function1;
import scala.concurrent.stm.*;
import scala.reflect.*;
import scala.runtime.AbstractFunction1;
import scala.runtime.BoxedUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class UntypedCoordinatedCounter extends UntypedActor {
    private String name;
    private Manifest<Integer> manifest = Manifest$.MODULE$.classType(Integer.class);
    private Ref<Integer> count = Ref$.MODULE$.apply(0, manifest);

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
                final Function1<Txn.Status, BoxedUnit> countDown = new AbstractFunction1<Txn.Status, BoxedUnit>() {
                    public BoxedUnit apply(Txn.Status status) {
                        latch.countDown(); return null;
                    }
                };
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).tell(coordinated.coordinate(coordMessage));
                }
                coordinated.atomic(new Atomically() {
                    public void atomically(InTxn txn) {
                        increment(txn);
                        Txn.afterCompletion(countDown, txn);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.single().get());
        }
    }
}
