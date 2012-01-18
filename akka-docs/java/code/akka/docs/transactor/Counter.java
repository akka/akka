/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor;

//#class
import akka.transactor.*;
import scala.concurrent.stm.Ref;
import static scala.concurrent.stm.JavaAPI.*;

public class Counter extends UntypedTransactor {
    Ref.View<Integer> count = newRef(0);

    public void atomically(Object message) {
        if (message instanceof Increment) {
            increment(count, 1);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getSender().tell(count.get());
            return true;
        } else return false;
    }
}
//#class
