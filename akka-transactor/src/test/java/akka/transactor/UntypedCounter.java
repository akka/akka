/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import java.util.List;
import java.util.Set;

import scala.concurrent.stm.Ref;
import scala.concurrent.stm.japi.STM;
import akka.actor.ActorRef;

public class UntypedCounter extends UntypedTransactor {
    private String name;
    private Ref.View<Integer> count = STM.newRef(0);

    public UntypedCounter(String name) {
        this.name = name;
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

    public void atomically(Object message) {
        if (message instanceof Increment) {
            STM.increment(count, 1);
            final Increment increment = (Increment) message;
            Runnable countDown = new Runnable() {
                public void run() {
                    increment.getLatch().countDown();
                }
            };
            STM.afterCompletion(countDown);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getSender().tell(count.get(), getSelf());
            return true;
        } else return false;
    }
}
