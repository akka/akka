/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.actor.typed
package internal
package adapter

import akka.actor.typed.EventStream
import akka.{ event ⇒ e }
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[typed] class EventStreamAdapter(val untyped: e.EventStream) extends EventStream {
  def logLevel: e.Logging.LogLevel = untyped.logLevel

  def publish[T](event: T): Unit = untyped.publish(event.asInstanceOf[AnyRef])

  def setLogLevel(loglevel: e.Logging.LogLevel): Unit = untyped.setLogLevel(loglevel)

  def subscribe[T](subscriber: ActorRef[T], to: Class[T]): Boolean =
    subscriber match {
      case adapter: ActorRefAdapter[_] ⇒ untyped.subscribe(adapter.untyped, to)
      case _ ⇒
        throw new UnsupportedOperationException("Cannot subscribe native typed ActorRef")
    }

  def unsubscribe[T](subscriber: ActorRef[T]): Unit =
    subscriber match {
      case adapter: ActorRefAdapter[_] ⇒ untyped.unsubscribe(adapter.untyped)
      case _ ⇒
        throw new UnsupportedOperationException("Cannot unsubscribe native typed ActorRef")
    }

  def unsubscribe[T](subscriber: ActorRef[T], from: Class[T]): Boolean =
    subscriber match {
      case adapter: ActorRefAdapter[_] ⇒ untyped.unsubscribe(adapter.untyped, from)
      case _ ⇒
        throw new UnsupportedOperationException("Cannot unsubscribe native typed ActorRef")
    }

}
