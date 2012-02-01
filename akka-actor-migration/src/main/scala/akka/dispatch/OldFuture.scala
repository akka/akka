/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import java.util.concurrent.TimeoutException
import scala.util.duration._
import akka.AkkaException
import akka.util.BoxedType
import scala.util.Duration
import akka.actor.GlobalActorSystem

/**
 * Some old methods made available through implicit conversion in
 * [[akka.migration]].
 */
@deprecated("use new Future api instead", "2.0")
class OldFuture[T](future: Future[T]) {

  @deprecated("use akka.dispatch.Await.result instead", "2.0")
  def get: T = try {
    Await.result(future, GlobalActorSystem.settings.ActorTimeout.duration)
  } catch {
    case e: TimeoutException ⇒ throw new FutureTimeoutException(e.getMessage, e)
  }

  @deprecated("use akka.dispatch.Await.ready instead", "2.0")
  def await: Future[T] = await(GlobalActorSystem.settings.ActorTimeout.duration)

  @deprecated("use akka.dispatch.Await.ready instead", "2.0")
  def await(atMost: Duration) = try {
    Await.ready(future, atMost)
    future
  } catch {
    case e: TimeoutException ⇒ throw new FutureTimeoutException(e.getMessage, e)
  }

  @deprecated("use new Future api instead", "2.0")
  def as[A](implicit m: Manifest[A]): Option[A] = {
    try await catch { case _: FutureTimeoutException ⇒ }
    future.value match {
      case None           ⇒ None
      case Some(Left(ex)) ⇒ throw ex
      case Some(Right(v)) ⇒ Some(BoxedType(m.erasure).cast(v).asInstanceOf[A])
    }
  }

  @deprecated("use new Future api instead", "2.0")
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

@deprecated("Await throws java.util.concurrent.TimeoutException", "2.0")
class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}