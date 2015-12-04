package akka.stream

class StreamLimitReachedException(val n: Long) extends RuntimeException(s"limit of $n reached")
