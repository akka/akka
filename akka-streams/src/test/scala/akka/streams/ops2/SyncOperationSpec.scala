package akka.streams.ops2

import scala.annotation.tailrec

trait SyncOperationSpec {
  abstract class DoNothing[O] extends SideEffect[O] {
    def runSideEffect(): Unit = ???
  }

  case class UpstreamRequestMore(n: Int) extends DoNothing[Nothing]
  case object UpstreamCancel extends DoNothing[Nothing]
  val upstream = new Upstream {
    val cancel: Result[Nothing] = UpstreamCancel
    val requestMore: (Int) ⇒ Result[Nothing] = UpstreamRequestMore
  }

  case class DownstreamNext[O](element: O) extends DoNothing[O]
  case object DownstreamComplete extends DoNothing[Nothing]
  case class DownstreamError(cause: Throwable) extends DoNothing[Nothing]
  val downstream = new Downstream[Float] {
    val next: (Float) ⇒ Result[Float] = DownstreamNext[Float]
    val complete: Result[Nothing] = DownstreamComplete
    val error: (Throwable) ⇒ Result[Float] = DownstreamError
  }

  implicit class AddRunOnce[O](result: Result[O]) {
    def runOnce(): Result[_] = result.asInstanceOf[Step[O]].run()
    def runToResult(): Result[_] = {
      @tailrec def rec(res: Result[_]): Result[O] =
        res match {
          case s: Step[_]         ⇒ rec(s.run())
          case CombinedResult(rs) ⇒ rs.fold(Continue: Result[O])(_ ~ _.runToResult().asInstanceOf[Result[O]]).asInstanceOf[Result[O]]
          case r                  ⇒ r.asInstanceOf[Result[O]]
        }
      rec(result)
    }
  }
}
