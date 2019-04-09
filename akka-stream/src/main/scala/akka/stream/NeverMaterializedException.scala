/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

final class NeverMaterializedException
    extends RuntimeException("Downstream canceled without triggering lazy source materialization")
