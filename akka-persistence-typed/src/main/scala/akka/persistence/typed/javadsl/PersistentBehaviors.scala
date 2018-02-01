/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.javadsl

import java.util.Collections
import java.util.function.BiConsumer

import akka.actor.typed.Behavior.UntypedBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.{ApiMayChange, DoNotInherit, InternalApi}
import akka.japi.{function => japi}
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.PersistentBehaviors._

import scala.collection.JavaConverters._
import akka.actor.typed.scaladsl.{ActorContext => SAC}

@ApiMayChange abstract class PersistentBehavior[Command, Event, State](val persistenceId: String) extends UntypedBehavior[Command] {

  def Effects: EffectFactories[Command, Event, State] = EffectFactory.asInstanceOf[EffectFactories[Command, Event, State]]

  val initialState: State

  def commandHandler(): CommandHandler[Command, Event, State]

  def eventHandler(): EventHandler[Event, State]

  /**
   * @return A new, mutable, builder
   */
  final def commandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(ctx: ActorContext[Command], state: State): Unit = { }

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * `predicate` receives the State, Event and the sequenceNr used for the Event
   */
  def snapshotOn(state: State, event: Event, sequenceNr: Long): Boolean = false
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
      (ctx, s) => onRecoveryCompleted(ctx.asJava, s),
      e => tagsFor(e).asScala.toSet,
      snapshotOn
    ).untypedProps
  }
}

object PersistentBehaviors {
  /**
   * Create a `Behavior` for a persistent actor.
   */
  def immutable[Command, Event, State](
    persistenceId:  String,
    initialState:   State,
    commandHandler: CommandHandler[Command, Event, State],
    eventHandler:   EventHandler[Event, State]): PersistentBehavior[Command, Event, State] =
    persistentEntity(_ ⇒ persistenceId, initialState, commandHandler, eventHandler)

  /**
   * Create a `Behavior` for a persistent actor in Cluster Sharding, when the persistenceId is not known
   * until the actor is started and typically based on the entityId, which
   * is the actor name.
   *
   * TODO This will not be needed when it can be wrapped in `Behaviors.deferred`.
   */
  def persistentEntity[Command, Event, State](
    persistenceIdFromActorName: String ⇒ String,
    initialState:               State,
    commandHandler:             CommandHandler[Command, Event, State],
    eventHandler:               EventHandler[Event, State]): PersistentBehavior[Command, Event, State] = ???


}
