/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import akka.actor.Actor.Receive
import akka.actor.ActorContext
import akka.actor.ActorCell
import akka.event.Logging.Debug

object LoggingReceive {

  /**
   * Wrap a Receive partial function in a logging enclosure, which sends a
   * debug message to the event bus each time before a message is matched.
   * This includes messages which are not handled.
   *
   * <pre><code>
   * def receive = LoggingReceive {
   *   case x => ...
   * }
   * </code></pre>
   *
   * This method does NOT modify the given Receive unless
   * akka.actor.debug.receive is set within akka.conf.
   */
  def apply(r: Receive)(implicit context: ActorContext): Receive = r match {
    case _: LoggingReceive ⇒ r
    case _                 ⇒ if (context.system.settings.AddLoggingReceive) new LoggingReceive(None, r) else r
  }
}

/**
 * This decorator adds invocation logging to a Receive function.
 * @param source the log source, if not defined the actor of the context will be used
 */
class LoggingReceive(source: Option[AnyRef], r: Receive)(implicit context: ActorContext) extends Receive {
  def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    val (str, clazz) = LogSource.fromAnyRef(source getOrElse context.asInstanceOf[ActorCell].actor)
    context.system.eventStream.publish(Debug(str, clazz, "received " + (if (handled) "handled" else "unhandled") + " message " + o))
    handled
  }
  def apply(o: Any): Unit = r(o)
}