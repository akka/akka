/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.transactor;

//#class
import akka.transactor.*;
import scala.concurrent.stm.Ref;
import scala.concurrent.stm.japi.STM;

public class Counter extends UntypedTransactor {
    Ref.View<Integer> count = STM.newRef(0);

    public void atomically(Object message) {
        if (message instanceof Increment) {
            STM.increment(count, 1);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getSender().tell(count.get(), getSelf());
            return true;
        } else return false;
    }
}
//#class
