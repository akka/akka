/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import language.implicitConversions
import akka.actor.ActorRef
import scala.concurrent.Future

package object channels {
  implicit def actorRefOps(ref: ActorRef) = new ActorRefOps(ref)
  implicit def futureOps[T](f: Future[T]) = new FutureOps(f)
  implicit def anyOps[T](x: T) = new AnyOps(x)
}
