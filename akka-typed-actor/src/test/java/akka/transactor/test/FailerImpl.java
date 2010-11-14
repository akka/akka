package akka.transactor.test;

import akka.actor.TypedActor;
import akka.stm.Ref;

public class FailerImpl extends TypedActor implements TypedCounter {
    private Ref<Integer> count = new Ref<Integer>(0);

    public void increment() {
        throw new RuntimeException("Expected failure");
    }

    public Integer get() {
        return count.get();
    }
}