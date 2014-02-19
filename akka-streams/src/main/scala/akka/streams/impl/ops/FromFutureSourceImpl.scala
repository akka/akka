package akka.streams.impl.ops

import akka.streams.impl._
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.util.Failure
import scala.Some
import scala.util.Success

class FromFutureSourceImpl[O](downstream: Downstream[O], ctx: ContextEffects, future: Future[O]) extends DynamicSyncSource {
  import ctx.executionContext

  override def start(): Effect =
    future.value match {
      case Some(Success(result)) ⇒ Continue // still need to wait for request
      case Some(Failure(cause))  ⇒ handleError(cause)
      case None                  ⇒ Continue // wait for a request before scheduling a completion handler
    }

  def initial: State = WaitingForRequest

  def WaitingForRequest = new State {
    def handleRequestMore(n: Int): Effect =
      future.value match {
        case Some(Success(result)) ⇒ handleSuccessfulResult(result)
        case Some(Failure(cause))  ⇒ handleError(cause)
        case None ⇒
          future.onComplete {
            case Success(result) ⇒ ctx.runInContext(handleSuccessfulResult(result))
            case Failure(cause)  ⇒ ctx.runInContext(handleError(cause))
          }
          Continue
      }

    def handleCancel(): Effect = ???
  }
  // invariants:
  //  - at least one element has been requested
  //  - future.onComplete has been called and we are waiting for the result
  def WaitingForResult = new State {
    def handleRequestMore(n: Int): Effect = Continue
    def handleCancel(): Effect = ???
  }
  def Completed = new State {
    def handleRequestMore(n: Int): Effect = ???
    def handleCancel(): Effect = ???
  }

  def handleSuccessfulResult(result: O): Effect = {
    become(Completed)
    downstream.next(result) ~ downstream.complete
  }
  def handleError(cause: Throwable): Effect = {
    become(Completed)
    downstream.error(cause)
  }
}
