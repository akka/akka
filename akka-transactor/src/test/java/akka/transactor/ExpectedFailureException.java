/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor;

public class ExpectedFailureException extends RuntimeException {
    public ExpectedFailureException() {
        super("Expected failure");
    }
}
