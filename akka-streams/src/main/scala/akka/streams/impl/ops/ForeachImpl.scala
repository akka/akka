package akka.streams
package impl
package ops

object ForeachImpl {
  def apply[I](upstream: Upstream, f: I ⇒ Unit, batchSize: Int = 100): SyncSink[I] =
    new SyncSink[I] {
      override def start(): Effect = upstream.requestMore(batchSize)

      def handleNext(element: I): Effect = {
        f(element)
        upstream.requestMore(1)
      }
      def handleComplete(): Effect = Continue
      def handleError(cause: Throwable): Effect = Continue
    }
}
