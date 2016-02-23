/**
 * Copyright (C) 2015 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

class StreamLimitReachedException(val n: Long) extends RuntimeException(s"limit of $n reached")
