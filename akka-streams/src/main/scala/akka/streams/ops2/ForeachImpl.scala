package akka.streams.ops2

object ForeachImpl {
  def apply[I](upstream: Upstream, f: I ⇒ Unit): SyncSink[I, I] =
    new SyncSink[I, I] {
      val batchSize = 100
      override def start(): Result[_] = upstream.requestMore(batchSize)

      def handleNext(element: I): Result[I] = {
        f(element)
        upstream.requestMore(1)
      }
      def handleComplete(): Result[I] = Continue
      def handleError(cause: Throwable): Result[I] = Continue
    }
}
