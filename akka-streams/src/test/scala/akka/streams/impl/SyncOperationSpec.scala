package akka.streams.impl

import scala.annotation.tailrec
import akka.streams.Operation.{ Sink, Source }
import rx.async.api.Producer
import rx.async.spi.Publisher
import akka.streams.impl.BasicEffects.HandleNextInSink

trait SyncOperationSpec {
  abstract class DoNothing extends ExternalEffect {
    def run(): Unit = ???
  }

  case class UpstreamRequestMore(n: Int) extends DoNothing
  case object UpstreamCancel extends DoNothing
  val upstream = new Upstream {
    val cancel: Effect = UpstreamCancel
    val requestMore: (Int) ⇒ Effect = UpstreamRequestMore
  }

  case class DownstreamNext[O](element: O) extends DoNothing
  case object DownstreamComplete extends DoNothing
  case class DownstreamError(cause: Throwable) extends DoNothing
  val downstream = new Downstream[Any] {
    val next: Any ⇒ Effect = DownstreamNext[Any]
    val complete: Effect = DownstreamComplete
    val error: Throwable ⇒ Effect = DownstreamError
  }

  implicit class AddRunOnce[O](result: Effect) {
    def runOnce(): Effect = result.asInstanceOf[SingleStep].runOne()
    def runToResult(trace: (Effect, Effect) ⇒ Unit = dontTrace): Effect = {
      @tailrec def rec(res: Effect): Effect =
        res match {
          case s: SingleStep ⇒ rec(s.runOne())
          case Effects(rs)   ⇒ rs.fold(Continue: Effect)(_ ~ _.runToResult(trace))
          case r             ⇒ r
        }

      val res = rec(result)
      trace(result, res)
      res
    }
  }

  val dontTrace: (Effect, Effect) ⇒ Unit = (_, _) ⇒ ()
  val printStep: (Effect, Effect) ⇒ Unit = (in, out) ⇒ println(s"$in => $out")

  trait NoOpSink[-I] extends SyncSink[I] {
    def handleNext(element: I): Effect = ???
    def handleComplete(): Effect = ???
    def handleError(cause: Throwable): Effect = ???
  }

  case class SubscribeTo[O](source: Source[O], onSubscribe: Upstream ⇒ (SyncSink[O], Effect)) extends DoNothing
  case class SubscribeFrom[O](sink: Sink[O], onSubscribe: Downstream[O] ⇒ (SyncSource, Effect)) extends DoNothing
  case class ExposedSource[O](source: Source[O]) extends Producer[O] {
    def getPublisher: Publisher[O] = throw new IllegalStateException("Should only be deconstructed")
  }
  object TestContextEffects extends ContextEffects {
    def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Effect)): Effect =
      source match {
        case InternalSource(h) ⇒ ContextEffects.subscribeToInternalSource(h, onSubscribe)
        case _                 ⇒ SubscribeTo(source, onSubscribe)
      }

    def subscribeFrom[O](sink: Sink[O])(onSubscribe: Downstream[O] ⇒ (SyncSource, Effect)): Effect = SubscribeFrom(sink, onSubscribe)
    def expose[O](source: Source[O]): Producer[O] = ExposedSource(source)
  }

  implicit class RichEffect(effect: Effect) {
    def expectHandleNextInSink[I](next: SyncSink[I]): I = effect match {
      case HandleNextInSink(`next`, value: I @unchecked) ⇒ value
      case x ⇒ throw new AssertionError(s"Expected HandleNextInSink but got $x")
    }
    def expectDownstreamNext[I](): I = effect match {
      case DownstreamNext(i: I @unchecked) ⇒ i
      case x                               ⇒ throw new AssertionError(s"Expected DownstreamNext but got $x")
    }
  }
  implicit class RichSource[I](source: Source[I]) {
    def expectInternalSourceHandler(): Downstream[I] ⇒ (SyncSource, Effect) = source match {
      case InternalSource(handler) ⇒ handler
      case x                       ⇒ throw new AssertionError(s"Expected InternalSource but got $x")
    }
  }

  case object TestException extends RuntimeException("This is a test exception")
}
