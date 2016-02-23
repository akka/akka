/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import language.existentials

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
   * `akka.actor.debug.receive` is set in configuration.
   */
  def apply(r: Receive)(implicit context: ActorContext): Receive = withLabel(null)(r)

  /**
   * Java API: compatible with lambda expressions
   * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
   */
  def create(r: Receive, context: ActorContext): Receive = apply(r)(context)

  /**
   * Create a decorated logger which will append `" in state " + label` to each message it logs.
   */
  def withLabel(label: String)(r: Receive)(implicit context: ActorContext): Receive = r match {
    case _: LoggingReceive ⇒ r
    case _                 ⇒ if (context.system.settings.AddLoggingReceive) new LoggingReceive(None, r, Option(label)) else r
  }
}

/**
 * This decorator adds invocation logging to a Receive function.
 * @param source the log source, if not defined the actor of the context will be used
 */
class LoggingReceive(source: Option[AnyRef], r: Receive, label: Option[String])(implicit context: ActorContext) extends Receive {
  def this(source: Option[AnyRef], r: Receive)(implicit context: ActorContext) = this(source, r, None)
  def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    if (context.system.eventStream.logLevel >= Logging.DebugLevel) {
      val (str, clazz) = LogSource.fromAnyRef(source getOrElse context.asInstanceOf[ActorCell].actor)
      context.system.eventStream.publish(Debug(str, clazz, "received " + (if (handled) "handled" else "unhandled") + " message " + o
        + " from " + context.sender()
        + (label match {
          case Some(l) ⇒ " in state " + l
          case _       ⇒ ""
        })))
    }
    handled
  }
  def apply(o: Any): Unit = r(o)
}
