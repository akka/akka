package akka.transactor.test;

import akka.transactor.typed.Coordinated;

public interface TypedCounter {
    @Coordinated public void increment();
    public Integer get();
}
