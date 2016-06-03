/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.util.Tupler
import akka.http.scaladsl.util.FastFuture._
import akka.pattern.{ CircuitBreaker, CircuitBreakerOpenException }
import akka.stream.scaladsl.Sink

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

// format: OFF

/**
 * @groupname future Future directives
 * @groupprio future 100
 */
trait FutureDirectives {

  import RouteDirectives._

  /**
   * "Unwraps" a `Future[T]` and runs the inner route after future
   * completion with the future's value as an extraction of type `Try[T]`.
   *
   * @group future
   */
  def onComplete[T](future: ⇒ Future[T]): Directive1[Try[T]] =
    Directive { inner ⇒ ctx ⇒
        import ctx.executionContext
        future.fast.transformWith(t ⇒ inner(Tuple1(t))(ctx))
    }

  /**
    * "Unwraps" a `Future[T]` and runs the inner route after future
    * completion with the future's value as an extraction of type `T` if
    * the supplied `CircuitBreaker` is closed.
    *
    * If the supplied [[CircuitBreaker]] is open the request is rejected
    * with a [[CircuitBreakerOpenRejection]].
    *
    * @group future
    */
  def onCompleteWithBreaker[T](breaker: CircuitBreaker)(future: ⇒ Future[T]): Directive1[Try[T]] =
    onComplete(breaker.withCircuitBreaker(future)).flatMap {
      case Failure(ex: CircuitBreakerOpenException) ⇒
        extractRequestContext.flatMap { ctx ⇒
          ctx.request.entity.dataBytes.runWith(Sink.cancelled)(ctx.materializer)
          reject(CircuitBreakerOpenRejection(ex))
        }
      case x ⇒ provide(x)
    }

  /**
   * "Unwraps" a `Future[T]` and runs the inner route after future
   * completion with the future's value as an extraction of type `T`.
   * If the future fails its failure Throwable is bubbled up to the nearest
   * ExceptionHandler.
   * If type `T` is already a Tuple it is directly expanded into the respective
   * number of extractions.
   *
   * @group future
   */
  def onSuccess(magnet: OnSuccessMagnet): Directive[magnet.Out] = magnet.directive

  /**
   * "Unwraps" a `Future[T]` and runs the inner route when the future has failed
   * with the future's failure exception as an extraction of type `Throwable`.
   * If the future succeeds the request is completed using the values marshaller
   * (This directive therefore requires a marshaller for the futures type to be
   * implicitly available.)
   *
   * @group future
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
