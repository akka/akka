/*
 * Copyright (C) 2015-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

class StreamLimitReachedException(val n: Long) extends RuntimeException(s"limit of $n reached")
