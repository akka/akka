package akka.transactor.test;

import akka.transactor.annotation.Coordinated;

public interface TypedCounter {
    @Coordinated public void increment();
    public Integer get();
}
