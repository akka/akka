/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

import akka.transactor.UntypedTransactor;
import scala.concurrent.stm.InTxn;

public class UntypedFailer extends UntypedTransactor {
    public void atomically(InTxn txn, Object message) throws Exception {
        throw new ExpectedFailureException();
    }
}
