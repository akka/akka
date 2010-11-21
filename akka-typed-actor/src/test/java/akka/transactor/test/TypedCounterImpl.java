package akka.transactor.test;

import akka.actor.TypedActor;
import akka.stm.Ref;

public class TypedCounterImpl extends TypedActor implements TypedCounter {
    private Ref<Integer> count = new Ref<Integer>(0);

    public void increment() {
        count.set(count.get() + 1);
    }

    public Integer get() {
        return count.get();
    }
}
