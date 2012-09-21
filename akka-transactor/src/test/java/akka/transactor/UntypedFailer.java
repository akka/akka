/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

public class UntypedFailer extends UntypedTransactor {
    public void atomically(Object message) throws Exception {
        throw new ExpectedFailureException();
    }
}
