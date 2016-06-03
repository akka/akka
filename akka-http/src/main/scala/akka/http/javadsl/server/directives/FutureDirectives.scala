/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import akka.http.javadsl.model.RequestEntity

import scala.compat.java8.FutureConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try
import akka.http.javadsl.server.{ Marshaller, Route }
import akka.http.scaladsl.server.directives.{ CompleteOrRecoverWithMagnet, FutureDirectives ⇒ D }
import akka.pattern.CircuitBreaker

abstract class FutureDirectives extends FormFieldDirectives {

  /**
   * "Unwraps" a `CompletionStage<T>` and runs the inner route after future
   * completion with the future's value as an extraction of type `Try<T>`.
   */
  def onComplete[T](f: Supplier[CompletionStage[T]], inner: JFunction[Try[T], Route]) = RouteAdapter {
    D.onComplete(f.get.toScala.recover(unwrapCompletionException)) { value ⇒
      inner(value).delegate
    }
  }

  /**
   * "Unwraps" a `CompletionStage[T]` and runs the inner route after future
   * completion with the future's value as an extraction of type `T` if
   * the supplied `CircuitBreaker` is closed.
   *
   * If the supplied [[CircuitBreaker]] is open the request is rejected
   * with a [[akka.http.javadsl.server.CircuitBreakerOpenRejection]].
   *
   * @group future
   */
  def onCompleteWithBreaker[T](breaker: CircuitBreaker, f: Supplier[CompletionStage[T]], inner: JFunction[Try[T], Route]) = RouteAdapter {
    D.onCompleteWithBreaker(breaker)(f.get.toScala.recover(unwrapCompletionException)) { value ⇒
      inner(value).delegate
    }
  }

  /**
   * "Unwraps" a `CompletionStage<T>` and runs the inner route after stage
   * completion with the stage's value as an extraction of type `T`.
   * If the stage fails its failure Throwable is bubbled up to the nearest
   * ExceptionHandler.
   */
  def onSuccess[T](f: Supplier[CompletionStage[T]], inner: JFunction[T, Route]) = RouteAdapter {
    D.onSuccess(f.get.toScala.recover(unwrapCompletionException)) { value ⇒
      inner(value).delegate
    }
  }

  /**
   * "Unwraps" a `CompletionStage<T>` and runs the inner route when the stage has failed
   * with the stage's failure exception as an extraction of type `Throwable`.
   * If the completion stage succeeds the request is completed using the values marshaller
   * (This directive therefore requires a marshaller for the completion stage value type to be
   * provided.)
   */
  def completeOrRecoverWith[T](f: Supplier[CompletionStage[T]], marshaller: Marshaller[T, RequestEntity], inner: JFunction[Throwable, Route]): Route = RouteAdapter {
    val magnet = CompleteOrRecoverWithMagnet(f.get.toScala)(Marshaller.asScalaEntityMarshaller(marshaller))
    D.completeOrRecoverWith(magnet) { ex ⇒ inner(ex).delegate }
  }

  // TODO: This might need to be raised as an issue to scala-java8-compat instead.
  // Right now, having this in Java:
  //     CompletableFuture.supplyAsync(() -> { throw new IllegalArgumentException("always failing"); })
  // will in fact fail the future with CompletionException.
  private def unwrapCompletionException[T]: PartialFunction[Throwable, T] = {
    case x: CompletionException if x.getCause ne null ⇒
      throw x.getCause
  }

}
