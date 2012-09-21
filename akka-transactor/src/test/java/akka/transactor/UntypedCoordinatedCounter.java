/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import scala.concurrent.stm.Ref;
import scala.concurrent.stm.japi.STM;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;

public class UntypedCoordinatedCounter extends UntypedActor {
    private String name;
    private Ref.View<Integer> count = STM.newRef(0);

    public UntypedCoordinatedCounter(String name) {
        this.name = name;
    }

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                List<ActorRef> friends = increment.getFriends();
                final CountDownLatch latch = increment.getLatch();
                final Runnable countDown = new Runnable() {
                    public void run() {
                        latch.countDown();
                    }
                };
                if (!friends.isEmpty()) {
                    Increment coordMessage = new Increment(friends.subList(1, friends.size()), latch);
                    friends.get(0).tell(coordinated.coordinate(coordMessage), getSelf());
                }
                coordinated.atomic(new Runnable() {
                    public void run() {
                        STM.increment(count, 1);
                        STM.afterCompletion(countDown);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.get(), getSelf());
        }
    }
}
