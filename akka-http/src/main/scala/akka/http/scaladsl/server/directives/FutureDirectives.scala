/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.server.util.Tupler
import akka.http.scaladsl.util.FastFuture._

// format: OFF

trait FutureDirectives {

  /**
   * "Unwraps" a `Future[T]` and runs the inner route after future
   * completion with the future's value as an extraction of type `Try[T]`.
   */
  def onComplete[T](future: ⇒ Future[T]): Directive1[Try[T]] =
    Directive { inner ⇒ ctx ⇒
        import ctx.executionContext
        future.fast.transformWith(t ⇒ inner(Tuple1(t))(ctx))
    }

  /**
   * "Unwraps" a `Future[T]` and runs the inner route after future
   * completion with the future's value as an extraction of type `T`.
   * If the future fails its failure Throwable is bubbled up to the nearest
   * ExceptionHandler.
   * If type `T` is already a Tuple it is directly expanded into the respective
   * number of extractions.
   */
  def onSuccess(magnet: OnSuccessMagnet): Directive[magnet.Out] = magnet.directive

  /**
   * "Unwraps" a `Future[T]` and runs the inner route when the future has failed
   * with the future's failure exception as an extraction of type `Throwable`.
   * If the future succeeds the request is completed using the values marshaller
   * (This directive therefore requires a marshaller for the futures type to be
   * implicitly available.)
   */
  def completeOrRecoverWith(magnet: CompleteOrRecoverWithMagnet): Directive1[Throwable] = magnet.directive
}

object FutureDirectives extends FutureDirectives

trait OnSuccessMagnet {
  type Out
  def directive: Directive[Out]
}

object OnSuccessMagnet {
  implicit def apply[T](future: ⇒ Future[T])(implicit tupler: Tupler[T]): OnSuccessMagnet { type Out = tupler.Out } =
    new OnSuccessMagnet {
      type Out = tupler.Out
      val directive = Directive[tupler.Out] { inner ⇒ ctx ⇒
        import ctx.executionContext
        future.fast.flatMap(t ⇒ inner(tupler(t))(ctx))
      }(tupler.OutIsTuple)
    }
}

trait CompleteOrRecoverWithMagnet {
  def directive: Directive1[Throwable]
}

object CompleteOrRecoverWithMagnet {
  implicit def apply[T](future: ⇒ Future[T])(implicit m: ToResponseMarshaller[T]): CompleteOrRecoverWithMagnet =
    new CompleteOrRecoverWithMagnet {
      val directive = Directive[Tuple1[Throwable]] { inner ⇒ ctx ⇒
        import ctx.executionContext
        future.fast.transformWith {
          case Success(res)   ⇒ ctx.complete(res)
          case Failure(error) ⇒ inner(Tuple1(error))(ctx)
        }
      }
    }
}
