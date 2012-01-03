/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import java.util.concurrent.TimeoutException
import akka.util.duration._
import akka.AkkaException

/**
 * Some old methods made available through implicit conversion in
 * [[akka.migration]].
 */
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

}

class FutureTimeoutException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}