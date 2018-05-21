/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.{ Collections, Optional }

import akka.actor.typed
import akka.actor.typed.Behavior
import akka.actor.typed.Behavior.DeferredBehavior
import akka.actor.typed.javadsl.ActorContext
import akka.annotation.ApiMayChange
import akka.persistence.typed._
import akka.persistence.typed.internal._

/** Java API */
@ApiMayChange
abstract class PersistentBehavior[Command, Event, State >: Null](val persistenceId: String) extends DeferredBehavior[Command] {

  /**
   * Factory of effects.
   *
   * Return effects from your handlers in order to instruct persistence on how to act on the incoming message (i.e. persist events).
   */
  protected final def Effect: EffectFactories[Command, Event, State] = EffectFactory.asInstanceOf[EffectFactories[Command, Event, State]]

  protected def commandHandler(): CommandHandler[Command, Event, State]

  /**
   * Implement by handling incoming commands and return an `Effect()` to persist or signal other effects
   * of the command handling such as stopping the behavior or others.
   *
   * This method is only invoked when the actor is running (i.e. not replaying).
   * While the actor is persisting events, the incoming messages are stashed and only
   * delivered to the handler once persisting them has completed.
   */
  protected def commandHandler(state: State): CommandHandler[Command, Event, State]

  protected def eventHandler(): EventHandler[Event, State]
  /**
   * Implement by applying the event to the current state in order to return a new state.
   *
   * This method invoked during recovery as well as running operation of this behavior,
   * in order to keep updating the state state.
   *
   * For that reason it is strongly discouraged to perform side-effects in this handler;
   * Side effects should be executed in `andThen` or `recoveryCompleted` blocks.
   */
  protected def eventHandler(state: State): EventHandler[Event, State]

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def commandHandlerBuilder(): CommandHandlerBuilder[Command, Event, State] =
    new CommandHandlerBuilder[Command, Event, State]()

  /**
   * @return A new, mutable, by state command handler builder
   */
  protected final def byStateCommandHandlerBuilder(currentState: State): ByStateCommandHandlerBuilder[Command, Event, State] =
    new ByStateCommandHandlerBuilder[Command, Event, State](currentState)

  /**
   * @return A new, mutable, event handler builder
   */
  protected final def eventHandlerBuilder(): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()

  /**
   * The `callback` function is called to notify the actor that the recovery process
   * is finished.
   */
  def onRecoveryCompleted(ctx: ActorContext[Command], state: Optional[State]): Unit = {}

  /**
   * Override and define that snapshot should be saved every N events.
   *
   * If this is overridden `shouldSnapshot` is not used.
   *
   * @return number of events between snapshots, should be greater than 0
   * @see [[PersistentBehavior#shouldSnapshot]]
   */
  def snapshotEvery(): Long = 0L

  /**
   * Initiates a snapshot if the given function returns true.
   * When persisting multiple events at once the snapshot is triggered after all the events have
   * been persisted.
   *
   * receives the State, Event and the sequenceNr used for the Event
   *
   * @return `true` if snapshot should be saved for the given event
   * @see [[PersistentBehavior#snapshotEvery]]
   */
  def shouldSnapshot(state: State, event: Event, sequenceNr: Long): Boolean = false

  /**
   * The `tagger` function should give event tags, which will be used in persistence query
   */
  def tagsFor(event: Event): java.util.Set[String] = Collections.emptySet()

  /**
   * INTERNAL API: DeferredBehavior init
   */
  override def apply(context: typed.ActorContext[Command]): Behavior[Command] = {

    val snapshotWhen: (State, Event, Long) ⇒ Boolean = { (state, event, seqNr) ⇒
      val n = snapshotEvery()
      if (n > 0)
        seqNr % n == 0
      else
        shouldSnapshot(state, event, seqNr)
    }

    val tagger: Event ⇒ Set[String] = { event ⇒
      import scala.collection.JavaConverters._
      val tags = tagsFor(event)
      if (tags.isEmpty) Set.empty
      else tags.asScala.toSet
    }

    scaladsl.PersistentBehaviors[Command, Event, State]
      .identifiedBy(persistenceId)
      .onCreation(
        (c, cmd) ⇒ commandHandler()(c.asJava, cmd).asInstanceOf[EffectImpl[Event, State]],
        eventHandler()(_)
      )
      .onUpdate(
        state ⇒ (c, cmd) ⇒ commandHandler(state)(c.asJava, cmd).asInstanceOf[EffectImpl[Event, State]],
        state ⇒ eventHandler(state)(_)
      )
      .onRecoveryCompleted((ctx, state) ⇒ onRecoveryCompleted(ctx.asJava, Optional.ofNullable(state.orNull)))
      .snapshotWhen(snapshotWhen)
      .withTagger(tagger)
  }

}

