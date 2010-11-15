package akka.transactor.example;

import akka.transactor.UntypedTransactor;
import akka.transactor.SendTo;
import akka.stm.Ref;

import java.util.Set;

public class UntypedCounter extends UntypedTransactor {
    Ref<Integer> count = new Ref<Integer>(0);

    @Override public Set<SendTo> coordinate(Object message) {
        if (message instanceof Increment) {
            Increment increment = (Increment) message;
            if (increment.hasFriend())
                return include(increment.getFriend(), new Increment());
        }
        return nobody();
    }

    public void atomically(Object message) {
        if (message instanceof Increment) {
            count.set(count.get() + 1);
        }
    }

    @Override public boolean normally(Object message) {
        if ("GetCount".equals(message)) {
            getContext().replyUnsafe(count.get());
            return true;
        } else return false;
    }
}
