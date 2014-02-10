package akka.streams.ops2

object ForeachImpl {
  def apply[I](upstream: Upstream, f: I ⇒ Unit): SyncSink[I] =
    new SyncSink[I] {
      val batchSize = 100
      override def start(): Result = upstream.requestMore(batchSize)

      def handleNext(element: I): Result = {
        f(element)
        upstream.requestMore(1)
      }
      def handleComplete(): Result = Continue
      def handleError(cause: Throwable): Result = Continue
    }
}
