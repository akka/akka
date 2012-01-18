/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import scala.concurrent.stm.InTxn;

public class UntypedFailer extends UntypedTransactor {
    public void atomically(Object message) throws Exception {
        throw new ExpectedFailureException();
    }
}
