/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.TimeoutException

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.util.{ LineNumbers, PrettyDuration }

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future }
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

/**
 * Stashing convenience behaviors that encapsulate common stashing patterns like awaiting a future to complete,
 * or collecting a number of futures or responses before continuing with further handling of incoming messages.
 */
object StashingBehaviors {

  private sealed trait Protocol
  private final case class FutureCompleted[T](value: Try[T]) extends Protocol

  final class StashFutureFailedException(msg: String, cause: Throwable)
    extends RuntimeException(msg, cause)

  /**
   * Keep stashing incoming messages until the passed in [[scala.concurrent.Future]] `f` completes successfully.
   *
   * See also [[untilSuccessful]] if you want to react only to the the future's success, and treat failure by
   * escalating it by failing the actor.
   */
  def until[T, M](f: Future[T], buffer: StashBuffer[M], timeout: FiniteDuration)(next: Try[T] ⇒ Behavior[M]): Behavior[M] = {
    Behaviors.setup[M] { context ⇒
      def name: String = s"until($f, $buffer, $timeout)(${LineNumbers(next)})"
      implicit def ec: ExecutionContextExecutor = context.executionContext

      f.value match {
        case Some(completed) ⇒ next(completed) // fast-path for already completed future
        case _ ⇒
          pipeToSelf(f, timeout, context, name _)
          Behaviors.receiveMessage[Any] {
            case FutureCompleted(t: Try[T @unchecked]) ⇒
              buffer.unstashAll(context, next(t)).asInstanceOf[Behavior[Any]]
            case msg: M @unchecked ⇒
              buffer.stash(msg)
              Behaviors.same
          }.narrow[M]
      }
    }
  }

  // TODO implement one in terms of the other

  /**
   * Keep stashing incoming messages until the passed in [[scala.concurrent.Future]] `f` completes successfully.
   *
   * Behavior fails with:
   *  - an [[StashFutureFailedException]] when the future is failed
   *  - an [[TimeoutException]] when the future does not complete within the `timeout`
   *
   * See also [[until]] if you want to react to either the future's success or failure.
   */
  def untilSuccessful[T, M](f: Future[T], buffer: StashBuffer[M], timeout: FiniteDuration)(next: T ⇒ Behavior[M]): Behavior[M] = {
    Behaviors.setup[M] { context ⇒
      def name: String = s"untilSuccessful($f, $buffer, $timeout)(${LineNumbers(next)})"
      implicit def ec: ExecutionContextExecutor = context.executionContext

      f.value match {
        case Some(Success(completed)) ⇒ next(completed) // fast-path for already completed future
        case Some(Failure(cause))     ⇒ throw new StashFutureFailedException(s"$name future failed immediately", cause)
        case _ ⇒
          pipeToSelf(f, timeout, context, name _)
          Behaviors.receiveMessage[Any] {
            case FutureCompleted(t: Success[T @unchecked]) ⇒
              buffer.unstashAll(context, next(t.value)).asInstanceOf[Behavior[Any]]
            case FutureCompleted(t: Failure[T @unchecked]) ⇒
              throw new StashFutureFailedException(s"$name future failed", t.exception)
            case msg: M @unchecked ⇒
              buffer.stash(msg)
              Behaviors.same
          }.narrow[M]
      }
    }
  }

  private def pipeToSelf[M, T](f: Future[T], timeout: FiniteDuration, context: ActorContext[M], name: () ⇒ String)(implicit ec: ExecutionContext): Unit = {
    def protocolCtx: ActorContext[Protocol] = context.asInstanceOf[ActorContext[Protocol]]
    val withTimeout = Future.firstCompletedOf(f :: timeoutFailure(name(), timeout, context.system) :: Nil)
    withTimeout.onComplete(t ⇒ protocolCtx.self ! FutureCompleted(t))
  }

  private def timeoutFailure[T](name: String, timeout: FiniteDuration, system: ActorSystem[_]): Future[T] = {
    val failed = Future.failed(new TimeoutException(s"$name behavior timed out after ${PrettyDuration.format(timeout)}"))
    akka.pattern.after(timeout, system.scheduler)(failed)(system.executionContext)
  }

}
