/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

import akka.actor.*;
import akka.transactor.*;
import scala.concurrent.stm.*;

public class Coordinator extends UntypedActor {
    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Message) {
                //#coordinated-atomic
                coordinated.atomic(new Atomically() {
                    public void atomically(InTxn txn) {
                        // do something in the coordinated transaction ...
                    }
                });
                //#coordinated-atomic
            }
        } else {
          unhandled(incoming);
        }
    }
}
