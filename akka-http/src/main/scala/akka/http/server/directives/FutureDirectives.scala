/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

import akka.http.util.FastFuture
import FastFuture._

import akka.http.marshalling.ToResponseMarshaller
import akka.http.server.util.Tupler

trait FutureDirectives {

  /**
   * "Unwraps" a ``Future[T]`` and runs its inner route after future
   * completion with the future's value as an extraction of type ``Try[T]``.
   */
  def onComplete[T](future: ⇒ Future[T]): Directive1[Try[T]] =
    new Directive1[Try[T]] {
      def tapply(f: Tuple1[Try[T]] ⇒ Route): Route = ctx ⇒
        future.fast.transformWith(t ⇒ f(Tuple1(t))(ctx))(ctx.executionContext)
    }

  /**
   * "Unwraps" a ``Future[T]`` and runs its inner route after future
   * completion with the future's value as an extraction of type ``T``.
   * If the future fails its failure Throwable is bubbled up to the nearest
   * ExceptionHandler.
   * If type ``T`` is already a Tuple it is directly expanded into the respective
   * number of extractions.
   */
  def onSuccess(magnet: OnSuccessMagnet): Directive[magnet.Out] = magnet.get

  /**
   * "Unwraps" a ``Future[T]`` and runs its inner route when the future has failed
   * with the future's failure exception as an extraction of type ``Throwable``.
   * If the future succeeds the request is completed using the values marshaller
   * (This directive therefore requires a marshaller for the futures type to be
   * implicitly available.)
   */
  def completeOrRecoverWith(magnet: CompleteOrRecoverWithMagnet): Directive1[Throwable] = magnet
}

object FutureDirectives extends FutureDirectives

trait OnSuccessMagnet {
  type Out
  def get: Directive[Out]
}

object OnSuccessMagnet {
  implicit def apply[T](future: ⇒ Future[T])(implicit tupler: Tupler[T]) =
    new Directive[tupler.Out]()(tupler.OutIsTuple) with OnSuccessMagnet {
      type Out = tupler.Out
      def get = this
      def tapply(f: Out ⇒ Route) = ctx ⇒ future.fast.flatMap(t ⇒ f(tupler(t))(ctx))(ctx.executionContext)
    }
}

trait CompleteOrRecoverWithMagnet extends Directive1[Throwable]

object CompleteOrRecoverWithMagnet {
  implicit def apply[T](future: ⇒ Future[T])(implicit m: ToResponseMarshaller[T]) =
    new CompleteOrRecoverWithMagnet {
      def tapply(f: Tuple1[Throwable] ⇒ Route) = ctx ⇒
        future.fast.transformWith {
          case Success(res)   ⇒ ctx.complete(res)
          case Failure(error) ⇒ f(Tuple1(error))(ctx)
        }(ctx.executionContext)
    }
}
