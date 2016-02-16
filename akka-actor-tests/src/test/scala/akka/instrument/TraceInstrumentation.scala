/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.ActorRef
import com.typesafe.config.Config
import scala.util.DynamicVariable

/**
 * Thread-local trace.
 */
object Trace {
  val empty = Trace("", ActorRef.noSender)

  val trace = new DynamicVariable[Trace](empty)

  def value: Trace = trace.value
  def value_=(newValue: AnyRef) = trace.value = newValue.asInstanceOf[Trace]

  def withValue[T](newValue: String)(thunk: ⇒ T) = trace.withValue(empty.copy(identifier = newValue))(thunk)
  def withValue[T](newValue: Trace)(thunk: ⇒ T) = trace.withValue(newValue)(thunk)

  def clear(): Unit = trace.value = empty
}

case class Trace(identifier: String, actorRef: ActorRef)

/**
 * Example instrumentation implementation that threads a context identifier
 * through message flows using the context and a thread-local.
 */
class TraceInstrumentation(config: Config) extends EmptyActorInstrumentation {
  override def actorCreated(actorRef: ActorRef): Unit = Trace.value match {
    case Trace(_, ActorRef.noSender) ⇒
    case t @ Trace(_, ref) ⇒
      ref ! t
      // The CallingThreadDispatcher will clear the trace for this thread during the message send/receive, so
      // you shouldn't send messages to test actors inside the instrumentation ;)
      Trace.value = t
  }

  override def actorTold(actorRef: ActorRef, message: Any, sender: ActorRef): AnyRef =
    Trace.value.copy(identifier = s"${Trace.value.identifier} <- ${actorRef.path.name}")

  override def actorReceived(actorRef: ActorRef, message: Any, sender: ActorRef, context: AnyRef): AnyRef = {
    Trace.value = context.asInstanceOf[Trace].copy(actorRef = actorRef)
    ActorInstrumentation.EmptyContext
  }

  override def clearContext(): Unit = Trace.clear()
}
