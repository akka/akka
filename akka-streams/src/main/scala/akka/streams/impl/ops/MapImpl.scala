package akka.streams.impl.ops

import akka.streams.impl._
import scala.util.control.NonFatal

class MapImpl[I, O](upstream: Upstream, downstream: Downstream[O], f: I ⇒ O) extends DynamicSyncOperation[I] {

  def initial = Running

  def Running = new State {
    def handleRequestMore(n: Int): Effect = upstream.requestMore(n)
    def handleCancel(): Effect = {
      become(Stopped)
      upstream.cancel
    }

    def handleNext(element: I): Effect =
      try {
        downstream.next(f(element))
      } catch {
        case NonFatal(ex) ⇒
          become(Stopped)
          downstream.error(ex) ~ upstream.cancel
      }

    def handleComplete(): Effect = {
      become(Stopped)
      downstream.complete
    }
    def handleError(cause: Throwable): Effect = {
      become(Stopped)
      downstream.error(cause)
    }
  }
  def Stopped = new State {
    def handleRequestMore(n: Int): Effect = errOut()
    def handleCancel(): Effect = errOut()

    def handleNext(element: I): Effect = errOut()
    def handleComplete(): Effect = errOut()
    def handleError(cause: Throwable): Effect = errOut()

    def errOut(): Nothing = throw new IllegalStateException("No events expected after complete/error/cancelled")
  }
}
