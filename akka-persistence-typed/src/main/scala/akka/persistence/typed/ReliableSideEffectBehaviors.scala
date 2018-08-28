/*
 * written as a Proof of Concept by Cyrille Chepelov
 *
 * Based on code Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehavior }

import scala.collection.{ immutable ⇒ im }
import scala.concurrent.Future

object ReliableSideEffectBehaviors {

  sealed trait RseEvent[Event, Notification] extends Serializable
  final case class WrappedEvent[Event, Notification](event: Event) extends RseEvent[Event, Notification]
  final case class IntentToNotifyEvents[Event, Notification](notificationNumbers: im.Seq[Long]) extends RseEvent[Event, Notification]
  final case class CompletedNotificationEvent[Event, Notification](notificationNumbers: im.Seq[Long]) extends RseEvent[Event, Notification]

  final case class RseState[State, Notification](
    state:                  State,
    nextNotificationSeqNr:  Long,
    requestedNotifications: im.IndexedSeq[(Long, Notification)],
    intendedNotifications:  im.IndexedSeq[(Long, Notification)]) extends Product with Serializable {
    def update(newState: State): RseState[State, Notification] = this.copy(state = newState)

    def addNotifications(notification: im.Seq[Notification]): RseState[State, Notification] = {
      val tagged = notification.to[Vector].zipWithIndex
        .map { case (nfy, idx) ⇒ (nextNotificationSeqNr + idx, nfy) }

      this.copy(nextNotificationSeqNr = nextNotificationSeqNr + tagged.size, requestedNotifications = requestedNotifications ++ tagged)
    }

    def markIntended(notificationSeqNrs: Iterable[Long]): RseState[State, Notification] = {
      val toSelect = notificationSeqNrs.to[Set]
      val (selected, remainingRequests) = requestedNotifications.partition { case (seqNr, nfy) ⇒ toSelect.contains(seqNr) }

      copy(requestedNotifications = remainingRequests, intendedNotifications = intendedNotifications ++ selected)
    }

    def removeNotifications(notificationSeqNrs: Iterable[Long]): RseState[State, Notification] = {
      val toRemove = notificationSeqNrs.to[Set]
      val remainingRequests = requestedNotifications.filterNot { case (seqNr, nfy) ⇒ toRemove.contains(seqNr) }
      val remainingIntents = intendedNotifications.filterNot { case (seqNr, nfy) ⇒ toRemove.contains(seqNr) }

      /* avoid wrap-around when it is safe to do so: */
      val fNextNotif = if ((nextNotificationSeqNr > 0x7000000000000000L) && remainingRequests.isEmpty && remainingIntents.isEmpty) {
        0L
      } else {
        nextNotificationSeqNr
      }

      copy(nextNotificationSeqNr = fNextNotif, requestedNotifications = remainingRequests, intendedNotifications = remainingIntents)
    }
  }
  object RseState {
    def apply[State, Notification](state: State): RseState[State, Notification] =
      new RseState[State, Notification](state, 0L, im.IndexedSeq.empty, im.IndexedSeq.empty)
  }

  sealed trait RseMiddleCommand
  sealed trait RseCommand
  private[akka] final case class CommandWrapper[Command](wrapped: Command) extends RseCommand with RseMiddleCommand
  private[akka] sealed trait RseInternalCommand extends RseCommand
  private[akka] final case object StartFlushIntents extends RseInternalCommand with RseMiddleCommand
  private[akka] final case class ExecuteFlushIntents(intentSeqN: im.Seq[Long]) extends RseInternalCommand
  private[akka] final case class RemoveIntents(intentSeqN: im.Seq[Long]) extends RseInternalCommand

  /**
   * Type alias for the command handler function for reacting on events having been persisted.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type CommandHandler[Command, Event, State] = (ActorContext[Command], State, Command) ⇒ Effect[Event, State]

  /**
   * Type alias for the event handler function defines how to act on commands.
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type EventHandler[State, Event, Notification] = (State, Event) ⇒ (State, im.Seq[Notification])

  /**
   * Type alias for the notification handler defined how to carry out a notification
   *
   * The type alias is not used in API signatures because it's easier to see (in IDE) what is needed
   * when full function type is used. When defining the handler as a separate function value it can
   * be useful to use the alias for shorter type signature.
   */
  type NotificationHandler[State, Notification] = (State, Notification) ⇒ Future[Unit]

  def receive[Command, Event, State, Notification](
    persistenceId:       String,
    emptyState:          State,
    commandHandler:      (ActorContext[Command], State, Command) ⇒ Effect[Event, State],
    eventHandler:        (State, Event) ⇒ (State, im.Seq[Notification]),
    notificationHandler: (State, Notification) ⇒ Future[Unit]): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    ReliableSideEffectBehaviorImpl[Command, Event, State, Notification](persistenceId, emptyState, commandHandler, eventHandler, notificationHandler)

}

trait ReliableSecondaryEffectBehavior[Command, Event, State, Notification] extends PersistentBehavior[Command, Event, State]
