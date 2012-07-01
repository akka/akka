/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.transactor;

//#class
import akka.actor.*;
import akka.transactor.*;
import scala.concurrent.stm.Ref;
import scala.concurrent.stm.japi.STM;

public class CoordinatedCounter extends UntypedActor {
    private Ref.View<Integer> count = STM.newRef(0);

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                if (increment.hasFriend()) {
                    increment.getFriend().tell(coordinated.coordinate(new Increment()));
                }
                coordinated.atomic(new Runnable() {
                    public void run() {
                        STM.increment(count, 1);
                    }
                });
            }
        } else if ("GetCount".equals(incoming)) {
            getSender().tell(count.get());
        } else {
          unhandled(incoming);
        }
    }
}
//#class
