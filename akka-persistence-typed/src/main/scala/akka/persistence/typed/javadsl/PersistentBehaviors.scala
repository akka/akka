/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.Collections

import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.ApiMayChange
import akka.persistence.typed._
import akka.persistence.typed.internal._

import scala.collection.JavaConverters._

@ApiMayChange abstract class PersistentBehavior[Command, Event, State >: Null](val persistenceId: String) extends UntypedBehavior[Command] {

  def Effect: EffectFactories[Command, Event, State] = EffectFactory.asInstanceOf[EffectFactories[Command, Event, State]]

  val initialState: State

  def commandHandler(): CommandHandler[Command, Event, State]

  def eventHandler(): EventHandler[Event, State]

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def commandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def byStateCommandHandlerBuilder(): ByStateCommandHandlerBuilder[Command, Event, State] =
    new ByStateCommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, builder
   */
  protected final def eventHandlerBuilder(): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(ctx: ActorContext[Command], state: State): Unit = {}

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def shouldSnapshot(state: State, event: Event, sequenceNr: Long): Boolean = false
  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(event: Event): java.util.Set[String] = Collections.emptySet()

  /**
   * INTERNAL API
   */
  override private[akka] def untypedProps = {
    new scaladsl.PersistentBehavior[Command, Event, State](
      _ ⇒ persistenceId,
      initialState,
      (ctx, s, e) ⇒ commandHandler.apply(ctx.asJava, s, e).asInstanceOf[EffectImpl[Event, State]],
      (s: State, e: Event) ⇒ eventHandler().apply(s, e),
      (ctx, s) ⇒ onRecoveryCompleted(ctx.asJava, s),
      e ⇒ tagsFor(e).asScala.toSet,
      shouldSnapshot
    ).untypedProps
  }
}

