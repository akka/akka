package akka.transactor.test;

import akka.transactor.UntypedTransactor;

public class UntypedFailer extends UntypedTransactor {
    public void atomically(Object message) throws Exception {
        throw new RuntimeException("Expected failure");
    }
}
