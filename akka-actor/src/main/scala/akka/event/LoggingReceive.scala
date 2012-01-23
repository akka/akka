/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.Actor.Receive
import akka.actor.ActorSystem
import akka.event.Logging.Debug

object LoggingReceive {

  /**
   * Wrap a Receive partial function in a logging enclosure, which sends a
   * debug message to the event bus each time before a message is matched.
   * This includes messages which are not handled.
   *
   * <pre><code>
   * def receive = LoggingReceive(this) {
   *   case x => ...
   * }
   * </code></pre>
   *
   * This method does NOT modify the given Receive unless
   * akka.actor.debug.receive is set within akka.conf.
   */
  def apply(source: AnyRef)(r: Receive)(implicit system: ActorSystem): Receive = r match {
    case _: LoggingReceive                       ⇒ r
    case _ if !system.settings.AddLoggingReceive ⇒ r
    case _                                       ⇒ new LoggingReceive(source, r)
  }
}

/**
 * This decorator adds invocation logging to a Receive function.
 */
class LoggingReceive(source: AnyRef, r: Receive)(implicit system: ActorSystem) extends Receive {
  def isDefinedAt(o: Any) = {
    val handled = r.isDefinedAt(o)
    val (str, clazz) = LogSource.fromAnyRef(source)
    system.eventStream.publish(Debug(str, clazz, "received " + (if (handled) "handled" else "unhandled") + " message " + o))
    handled
  }
  def apply(o: Any): Unit = r(o)
}