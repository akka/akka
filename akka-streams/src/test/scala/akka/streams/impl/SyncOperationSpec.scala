package akka.streams.impl

import scala.annotation.tailrec

trait SyncOperationSpec {
  abstract class DoNothing[O] extends SideEffect {
    def run(): Unit = ???
  }

  case class UpstreamRequestMore(n: Int) extends DoNothing[Nothing]
  case object UpstreamCancel extends DoNothing[Nothing]
  val upstream = new Upstream {
    val cancel: Effect = UpstreamCancel
    val requestMore: (Int) ⇒ Effect = UpstreamRequestMore
  }

  case class DownstreamNext[O](element: O) extends DoNothing[O]
  case object DownstreamComplete extends DoNothing[Nothing]
  case class DownstreamError(cause: Throwable) extends DoNothing[Nothing]
  val downstream = new Downstream[Float] {
    val next: (Float) ⇒ Effect = DownstreamNext[Float]
    val complete: Effect = DownstreamComplete
    val error: (Throwable) ⇒ Effect = DownstreamError
  }

  implicit class AddRunOnce[O](result: Effect) {
    def runOnce(): Effect = result.asInstanceOf[SingleStep].runOne()
    def runToResult(): Effect = {
      @tailrec def rec(res: Effect): Effect =
        res match {
          case s: SingleStep ⇒ rec(s.runOne())
          case Effects(rs)   ⇒ rs.fold(Continue: Effect)(_ ~ _.runToResult())
          case r             ⇒ r
        }
      rec(result)
    }
  }
}
