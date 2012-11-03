/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.implicitConversions

import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{ Failure, Success }
import akka.actor.{ Status, ActorRef, Actor }

trait PipeToSupport {

  final class PipeableFuture[T](val future: Future[T])(implicit executionContext: ExecutionContext) {
    def pipeTo(recipient: ActorRef)(implicit sender: ActorRef = Actor.noSender): Future[T] = {
      future onComplete {
        case Success(r) ⇒ recipient ! r
        case Failure(f) ⇒ recipient ! Status.Failure(f)
      }
      future
    }
    def to(recipient: ActorRef): PipeableFuture[T] = to(recipient, null)
    def to(recipient: ActorRef, sender: ActorRef): PipeableFuture[T] = {
      pipeTo(recipient)(sender)
      this
    }
  }

  /**
   * Import this implicit conversion to gain the `pipeTo` method on [[scala.concurrent.Future]]:
   *
   * {{{
   * import akka.pattern.pipe
   *
   * Future { doExpensiveCalc() } pipeTo nextActor
   *
   * or
   *
   * pipe(someFuture) to nextActor
   *
   * }}}
   */
  implicit def pipe[T](future: Future[T])(implicit executionContext: ExecutionContext): PipeableFuture[T] = new PipeableFuture(future)
}