/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import akka.dispatch.Future
import akka.dispatch.OldFuture
import akka.util.Timeout
import akka.actor.GlobalActorSystem

package object migration {

  implicit def future2OldFuture[T](future: Future[T]): OldFuture[T] = new OldFuture[T](future)

  implicit def askTimeout: Timeout = GlobalActorSystem.settings.ActorTimeout

}