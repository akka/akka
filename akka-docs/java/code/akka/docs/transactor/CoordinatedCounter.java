/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

//#class
import akka.actor.*;
import akka.transactor.*;
import scala.concurrent.stm.*;

public class CoordinatedCounter extends UntypedActor {
    private Ref<Integer> count = Stm.ref(0);

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
                if (increment.hasFriend()) {
                    increment.getFriend().tell(coordinated.coordinate(new Increment()));
                }
                coordinated.atomic(new Atomically() {
                    public void atomically(InTxn txn) {
                        increment(txn);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.single().get());
        } else {
          unhandled(incoming);
        }
    }
}
//#class
