package akka.stream

/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
class StreamLimitReachedException(val n: Long) extends RuntimeException(s"limit of $n reached")
