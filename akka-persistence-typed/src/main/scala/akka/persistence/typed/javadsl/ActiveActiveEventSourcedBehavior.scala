/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.Optional

import akka.actor.typed.BackoffSupervisorStrategy
import akka.actor.typed.Behavior
import akka.actor.typed.TypedActorContext
import akka.annotation.InternalApi
import akka.persistence.typed.internal
import akka.persistence.typed.internal.EffectImpl
import akka.persistence.typed.scaladsl.ActiveActiveContextImpl

abstract class ActiveActiveEventSourcedBehavior[Command, Event, State](
    activeActiveContext: ActiveActiveContext,
    onPersistFailure: Optional[BackoffSupervisorStrategy])
    extends EventSourcedBehavior[Command, Event, State](activeActiveContext.persistenceId, onPersistFailure) {

  def this(activeActiveContext: ActiveActiveContext) = this(activeActiveContext, Optional.empty())

  protected def getActiveActiveContext(): ActiveActiveContext = activeActiveContext

  /**
   * INTERNAL API: DeferredBehavior init, not for user extension
   */
  @InternalApi override def apply(context: TypedActorContext[Command]): Behavior[Command] = {
    // Note: duplicated in EventSourcedBehavior to not break source compatibility
    val snapshotWhen: (State, Event, Long) => Boolean = (state, event, seqNr) => shouldSnapshot(state, event, seqNr)

    val tagger: Event => Set[String] = { event =>
      import akka.util.ccompat.JavaConverters._
      val tags = tagsFor(event)
      if (tags.isEmpty) Set.empty
      else tags.asScala.toSet
    }

    val behavior = new internal.EventSourcedBehaviorImpl[Command, Event, State](
      persistenceId,
      emptyState,
      (state, cmd) => commandHandler()(state, cmd).asInstanceOf[EffectImpl[Event, State]],
      eventHandler()(_, _),
      getClass)
      .snapshotWhen(snapshotWhen)
      .withRetention(retentionCriteria.asScala)
      .withTagger(tagger)
      .eventAdapter(eventAdapter())
      .snapshotAdapter(snapshotAdapter())
      .withJournalPluginId(journalPluginId)
      .withSnapshotPluginId(snapshotPluginId)
      .withRecovery(recovery.asScala)
      // context not user extendable so there should never be any other impls
      .withActiveActive(activeActiveContext.asInstanceOf[ActiveActiveContextImpl])

    val handler = signalHandler()
    val behaviorWithSignalHandler =
      if (handler.isEmpty) behavior
      else behavior.receiveSignal(handler.handler)

    if (onPersistFailure.isPresent)
      behaviorWithSignalHandler.onPersistFailure(onPersistFailure.get)
    else
      behaviorWithSignalHandler
  }
}
