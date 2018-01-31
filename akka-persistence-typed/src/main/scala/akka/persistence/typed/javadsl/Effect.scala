/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl

import akka.annotation.DoNotInherit
import akka.persistence.typed.scaladsl.PersistentBehaviors._
import akka.japi.{ function ⇒ japi }

import scala.collection.JavaConverters._

object Effect {
  /**
   * Persist a single event
   */
  final def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist all of a the given events. Each event will be applied through `applyEffect` separately but not until
   * all events has been persisted. If an `afterCallBack` is added through [[Effect#andThen]] that will invoked
   * after all the events has been persisted.
   */
  final def persist[Event, State](events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

  /**
   * Do not persist anything
   */
  def none[Event, State]: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor
   */
  def stop[Event, State]: Effect[Event, State] = Stop.asInstanceOf[ChainableEffect[Event, State]]

  /**
   * This command is not handled, but it is not an error that it isn't.
   */
  def unhandled[Event, State]: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]
}

@DoNotInherit abstract class Effect[+Event, State] {
  self: EffectImpl[Event, State] ⇒
  /** Convenience method to register a side effect with just a callback function */
  final def andThen(callback: japi.Procedure[State]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[Event, State](s ⇒ callback.apply(s)))
}
