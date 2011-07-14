/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.dispatch.{ FutureTimeoutException, Future }
import akka.util.Helpers.{ narrow, narrowSilently }

package object akka {
  /**
   * Implicitly converts the given Option[Any] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   */
  implicit def toAnyOptionAsTypedOption(anyOption: Option[Any]) = new AnyOptionAsTypedOption(anyOption)

  /**
   * Implicitly converts the given Future[_] to a AnyOptionAsTypedOption which offers the method <code>as[T]</code>
   * to convert an Option[Any] to an Option[T].
   * This means that the following code is equivalent:
   *   (actor ? "foo").as[Int] (Recommended)
   */
  implicit def futureToAnyOptionAsTypedOption(anyFuture: Future[_]) = new AnyOptionAsTypedOption({
    try { anyFuture.await } catch { case t: FutureTimeoutException ⇒ }
    anyFuture.resultOrException
  })

  private[akka] class AnyOptionAsTypedOption(anyOption: Option[Any]) {
    /**
     * Convenience helper to cast the given Option of Any to an Option of the given type. Will throw a ClassCastException
     * if the actual type is not assignable from the given one.
     */
    def as[T]: Option[T] = narrow[T](anyOption)

    /**
     * Convenience helper to cast the given Option of Any to an Option of the given type. Will swallow a possible
     * ClassCastException and return None in that case.
     */
    def asSilently[T: Manifest]: Option[T] = narrowSilently[T](anyOption)
  }
}
