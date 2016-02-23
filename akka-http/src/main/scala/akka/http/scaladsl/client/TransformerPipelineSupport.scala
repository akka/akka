/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.client

import scala.concurrent.{ Future, ExecutionContext }
import akka.event.{ Logging, LoggingAdapter }

trait TransformerPipelineSupport {

  def logValue[T](log: LoggingAdapter, level: Logging.LogLevel = Logging.DebugLevel): T ⇒ T =
    logValue { value ⇒ log.log(level, value.toString) }

  def logValue[T](logFun: T ⇒ Unit): T ⇒ T = { response ⇒
    logFun(response)
    response
  }

  implicit class WithTransformation[A](value: A) {
    def ~>[B](f: A ⇒ B): B = f(value)
  }

  implicit class WithTransformerConcatenation[A, B](f: A ⇒ B) extends (A ⇒ B) {
    def apply(input: A) = f(input)
    def ~>[AA, BB, R](g: AA ⇒ BB)(implicit aux: TransformerAux[A, B, AA, BB, R]) =
      new WithTransformerConcatenation[A, R](aux(f, g))
  }
}

object TransformerPipelineSupport extends TransformerPipelineSupport

trait TransformerAux[A, B, AA, BB, R] {
  def apply(f: A ⇒ B, g: AA ⇒ BB): A ⇒ R
}

object TransformerAux {
  implicit def aux1[A, B, C]: TransformerAux[A, B, B, C, C] = new TransformerAux[A, B, B, C, C] {
    def apply(f: A ⇒ B, g: B ⇒ C): A ⇒ C = f andThen g
  }
  implicit def aux2[A, B, C](implicit ec: ExecutionContext): TransformerAux[A, Future[B], B, C, Future[C]] =
    new TransformerAux[A, Future[B], B, C, Future[C]] {
      def apply(f: A ⇒ Future[B], g: B ⇒ C): A ⇒ Future[C] = f(_).map(g)
    }
  implicit def aux3[A, B, C](implicit ec: ExecutionContext): TransformerAux[A, Future[B], B, Future[C], Future[C]] =
    new TransformerAux[A, Future[B], B, Future[C], Future[C]] {
      def apply(f: A ⇒ Future[B], g: B ⇒ Future[C]): A ⇒ Future[C] = f(_).flatMap(g)
    }
}