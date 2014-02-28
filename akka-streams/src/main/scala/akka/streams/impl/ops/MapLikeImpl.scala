package akka.streams.impl.ops

import akka.streams.impl.{ Effect, Downstream, Upstream, DynamicSyncOperation }
import scala.util.control.NonFatal

abstract class MapLikeImpl[I, O] extends DynamicSyncOperation[I] {
  def upstream: Upstream
  def downstream: Downstream[O]
  def map(i: I): O

  def initial = Running

  def Running = new State {
    def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
    def handleCancel(): Effect = {
      become(Stopped("cancelled"))
      upstream.cancel
    }

    def handleNext(element: I): Effect =
      try {
        downstream.next(map(element))
      } catch {
        case NonFatal(ex) ⇒
          become(Stopped("error"))
          downstream.error(ex) ~ upstream.cancel
      }

    def handleComplete(): Effect = {
      become(Stopped("completed"))
      downstream.complete
    }
    def handleError(cause: Throwable): Effect = {
      become(Stopped("error"))
      downstream.error(cause)
    }
  }
}
