package akka.stream

class StreamLimitReachedException(val n: Int) extends RuntimeException(s"limit of $n reached")
