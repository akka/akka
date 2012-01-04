/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import java.util.concurrent.TimeoutException
import akka.util.duration._
import akka.AkkaException
import akka.util.BoxedType

/**
 * Some old methods made available through implicit conversion in
 * [[akka.migration]].
 */
@deprecated("use new Future api instead", "2.0")
class OldFuture[T](future: Future[T]) {

  def get: T = try {
    Await.result(future, 5 seconds)
  } catch {
    case e: TimeoutException ⇒ throw new FutureTimeoutException(e.getMessage, e)
  }

  def await: Future[T] = try {
    Await.ready(future, 5 seconds)
    future
  } catch {
    case e: TimeoutException ⇒ throw new FutureTimeoutException(e.getMessage, e)
  }

  def as[A](implicit m: Manifest[A]): Option[A] = {
    try await catch { case _: FutureTimeoutException ⇒ }
    future.value match {
      case None           ⇒ None
      case Some(Left(ex)) ⇒ throw ex
      case Some(Right(v)) ⇒ Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
    }
  }

  def asSilently[A](implicit m: Manifest[A]): Option[A] = {
    try await catch { case _: FutureTimeoutException ⇒ }
    future.value match {
      case None           ⇒ None
      case Some(Left(ex)) ⇒ throw ex
      case Some(Right(v)) ⇒
        try Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
        catch { case _: ClassCastException ⇒ None }
    }
  }

}

class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}