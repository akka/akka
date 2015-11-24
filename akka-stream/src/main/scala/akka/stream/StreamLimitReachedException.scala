package akka.stream

class StreamLimitReachedException(val n: Int, msg: String) extends RuntimeException(msg) {
  def getLimit = n
}
