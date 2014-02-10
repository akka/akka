package akka.streams.ops2

import scala.annotation.tailrec

trait SyncOperationSpec {
  abstract class DoNothing[O] extends SideEffect {
    def runSideEffect(): Unit = ???
  }

  case class UpstreamRequestMore(n: Int) extends DoNothing[Nothing]
  case object UpstreamCancel extends DoNothing[Nothing]
  val upstream = new Upstream {
    val cancel: Result = UpstreamCancel
    val requestMore: (Int) ⇒ Result = UpstreamRequestMore
  }

  case class DownstreamNext[O](element: O) extends DoNothing[O]
  case object DownstreamComplete extends DoNothing[Nothing]
  case class DownstreamError(cause: Throwable) extends DoNothing[Nothing]
  val downstream = new Downstream[Float] {
    val next: (Float) ⇒ Result = DownstreamNext[Float]
    val complete: Result = DownstreamComplete
    val error: (Throwable) ⇒ Result = DownstreamError
  }

  implicit class AddRunOnce[O](result: Result) {
    def runOnce(): Result = result.asInstanceOf[Step].run()
    def runToResult(): Result = {
      @tailrec def rec(res: Result): Result =
        res match {
          case s: Step            ⇒ rec(s.run())
          case CombinedResult(rs) ⇒ rs.fold(Continue: Result)(_ ~ _.runToResult())
          case r                  ⇒ r
        }
      rec(result)
    }
  }
}
