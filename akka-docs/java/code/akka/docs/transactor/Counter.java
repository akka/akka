/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

//#class
import akka.transactor.*;
import scala.concurrent.stm.*;

public class Counter extends UntypedTransactor {
    Ref<Integer> count = Stm.ref(0);

    public void atomically(InTxn txn, Object message) {
        if (message instanceof Increment) {
            Integer newValue = count.get(txn) + 1;
            count.set(newValue, txn);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getSender().tell(count.single().get());
            return true;
        } else return false;
    }
}
//#class
