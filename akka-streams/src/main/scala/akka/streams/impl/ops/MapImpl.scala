package akka.streams
package impl
package ops

class MapImpl[I, O](upstream: Upstream, downstream: Downstream[O], f: I ⇒ O) extends SyncOperation[I] {
  def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
  def handleCancel(): Effect = upstream.cancel

  def handleNext(element: I): Effect = downstream.next(f(element))
  def handleComplete(): Effect = downstream.complete
  def handleError(cause: Throwable): Effect = downstream.error(cause)
}
