/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.implicitConversions

import scala.concurrent.{ Future }
import akka.actor.{ Status, ActorRef }

trait PipeToSupport {

  final class PipeableFuture[T](val future: Future[T]) {
    def pipeTo(recipient: ActorRef)(implicit sender: ActorRef = null): Future[T] = {
      future onComplete {
        case Right(r) ⇒ recipient ! r
        case Left(f)  ⇒ recipient ! Status.Failure(f)
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
  implicit def pipe[T](future: Future[T]): PipeableFuture[T] = new PipeableFuture(future)
}