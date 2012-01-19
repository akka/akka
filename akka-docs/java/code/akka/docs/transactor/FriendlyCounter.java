/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

//#class
import akka.actor.*;
import akka.transactor.*;
import java.util.Set;
import scala.concurrent.stm.*;

public class FriendlyCounter extends UntypedTransactor {
    Ref<Integer> count = Stm.ref(0);

    @Override public Set<SendTo> coordinate(Object message) {
        if (message instanceof Increment) {
            Increment increment = (Increment) message;
            if (increment.hasFriend())
                return include(increment.getFriend(), new Increment());
        }
        return nobody();
    }

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
