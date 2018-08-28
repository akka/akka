/*
 * written as a Proof of Concept by Cyrille Chepelov
 *
 * Based on code Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.Done
import akka.actor.{ ActorPath, typed }
import akka.actor.typed.{ BackoffSupervisorStrategy, Behavior, SupervisorStrategy, Terminated }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.persistence.typed.internal._
import akka.persistence.{ Recovery, SnapshotMetadata, SnapshotSelectionCriteria }

import scala.collection.{ immutable ⇒ im }
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehavior, PersistentBehaviors }
import akka.util.ConstantFun

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import scala.language.existentials

class NotificationDeliveryFailure(from: ActorPath, inner: Throwable) extends Exception(inner) {
  override def getMessage: String = s"from actor ${from}"
}
class MultipleNotificationDeliveryFailures(from: ActorPath, throwables: Seq[Throwable]) extends Exception {
  override def getMessage: String = s"from actor ${from}\n" + throwables.zipWithIndex.map {
    case (ex, idx) ⇒ s"   Mdnf[${idx}] → ${ex.toString}"
  }.mkString("\n")
}

case class ReliableSideEffectBehaviorImpl[Command, Event, State, Notification](persistenceId: String, emptyState: State,
                                                                               commandHandler:      ReliableSideEffectBehaviors.CommandHandler[Command, Event, State],
                                                                               eventHandler:        ReliableSideEffectBehaviors.EventHandler[State, Event, Notification],
                                                                               notificationHandler: ReliableSideEffectBehaviors.NotificationHandler[State, Notification],

                                                                               journalPluginId:     Option[String]                                                      = None,
                                                                               snapshotPluginId:    Option[String]                                                      = None,
                                                                               recoveryCompleted:   (ActorContext[Command], State) ⇒ Unit                               = ConstantFun.scalaAnyTwoToUnit,
                                                                               tagger:              Event ⇒ Set[String]                                                 = (_: Event) ⇒ Set.empty[String],
                                                                               eventAdapter:        EventAdapter[Event, _]                                              = NoOpEventAdapter.instance[Event],
                                                                               snapshotCriteria:    Option[Either[Long, (State, Event, Long) ⇒ Boolean]]                = None,
                                                                               recovery:            Recovery                                                            = Recovery(),
                                                                               supervisionStrategy: SupervisorStrategy                                                  = SupervisorStrategy.stop,
                                                                               afterSnapshot:       Option[(ActorContext[Command], SnapshotMetadata, Try[Done]) ⇒ Unit] = None)
  extends ReliableSecondaryEffectBehavior[Command, Event, State, Notification] {

  import ReliableSideEffectBehaviors._

  private val EffectStop = Effect.stop[Event, State]

  def wrapEffect(value: Effect[Event, State]): Effect[RseEvent[Event, Notification], RseState[State, Notification]] = value match {
    case _ if value == Unhandled      ⇒ Effect.unhandled
    case _ if value == PersistNothing ⇒ Effect.none

    case impl: EffectImpl[Event, State] ⇒
      val wrappedEvents = impl.events.map(WrappedEvent.apply[Event, Notification])

      val wrappedSideEffects: im.Seq[SideEffect[RseState[State, Notification]]] = {
        val implSideEffects = impl match {
          case ce: CompositeEffect[Event, State] @unchecked ⇒
            ce._sideEffects
          case _ ⇒
            Nil
        }

        implSideEffects.map {
          case se: Callback[State] @unchecked ⇒
            SideEffect[RseState[State, Notification]](state ⇒ se.effect(state.state))
          case se if se == Stop ⇒
            SideEffect.stop[RseState[State, Notification]]
        }
      }

      val mainPersist: Effect[RseEvent[Event, Notification], RseState[State, Notification]] = wrappedEvents match {
        case _ if wrappedEvents.isEmpty   ⇒ Effect.none
        case _ if wrappedEvents.size == 1 ⇒ Effect.persist(wrappedEvents.head)
        case _                            ⇒ Effect.persist(wrappedEvents)
      }

      if (wrappedSideEffects.isEmpty) mainPersist else
        mainPersist match {
          case mp: EffectImpl[RseEvent[Event, Notification], RseState[State, Notification]] @unchecked ⇒
            new CompositeEffect(mp, wrappedSideEffects)
        }
  }

  def tryFlushNotifications(ctx: ActorContext[RseCommand], intentsToBeFlushed: im.Seq[(Long, Notification)])(state: RseState[State, Notification]): Unit = {
    import ctx.executionContext

    val fNotificationResults = intentsToBeFlushed.map {
      case (nfySeqNr, nfy) ⇒
        val fNotified: Future[(Long, Try[Unit])] = notificationHandler(state.state, nfy)
          // 2.12: .transform & go
          .map(x ⇒ nfySeqNr → Success(x))
          .recoverWith { case t: Throwable ⇒ Future.successful(nfySeqNr → Failure(t)) }

        fNotified
    }

    val fResult = Future.sequence(fNotificationResults)
    fResult.foreach { result ⇒
      val completedNotifications = result.collect {
        case (nfySeqNr, Success(_)) ⇒ nfySeqNr
      }
      if (completedNotifications.nonEmpty) {
        ctx.self ! RemoveIntents(completedNotifications)
      }

      val failedNotifications = result.collect {
        case (nfySeqNr, Failure(ex)) ⇒ ex
      }
      if (failedNotifications.nonEmpty) {
        if (failedNotifications.tail.isEmpty) {
          throw new NotificationDeliveryFailure(ctx.self.path, failedNotifications.head)
        } else {
          throw new MultipleNotificationDeliveryFailures(ctx.self.path, failedNotifications)
        }
      }
    }

  }

  type IPB = PersistentBehavior[RseCommand, RseEvent[Event, Notification], RseState[State, Notification]]

  private def wrappingTagger(e: RseEvent[Event, Notification]): Set[String] = e match {
    case WrappedEvent(event) ⇒ this.tagger(event)
    case _                   ⇒ Set.empty
  }

  override def apply(wrappedCtx: typed.ActorContext[Command]): Behavior[Command] = {
    val rawInternal =
      PersistentBehaviors.receive[RseCommand, RseEvent[Event, Notification], RseState[State, Notification]](
        persistenceId = persistenceId,
        emptyState = RseState(emptyState),
        commandHandler = internalCommandHandler(wrappedCtx.asScala),
        eventHandler = internalEventHandler)

    val transformers: Seq[IPB ⇒ IPB] =
      this.journalPluginId.map(s ⇒ (pb: IPB) ⇒ pb.withJournalPluginId(s)).getOrElse((pb: IPB) ⇒ pb) ::
        this.snapshotPluginId.map(s ⇒ (pb: IPB) ⇒ pb.withSnapshotPluginId(s)).getOrElse((pb: IPB) ⇒ pb) ::
        ((pb: IPB) ⇒ pb.withTagger(wrappingTagger)) ::
        ((pb: IPB) ⇒ if (eventAdapter == NoOpEventAdapter.instance[Event]) pb
        else {
          /* the problem is, even if we know the type of the written payload, the internal eventAdapter does not
            * know how to persist our wrapper… */
          ???
        }) ::
        ((pb: IPB) ⇒ snapshotCriteria match {
          case None              ⇒ pb
          case Some(Left(every)) ⇒ pb.snapshotEvery(every)
          case Some(Right(method)) ⇒
            pb.snapshotWhen {
              case (rseState, WrappedEvent(event), seqNumber) ⇒
                /* FIXME : this may miss a beat when the wrapped method doesn't care for the event but does a simple
                'modulo' operation on the seqNumber. E.g. "snapshotEvery" has been called. */
                method(rseState.state, event, seqNumber)
              case (rseState, otherEvent, seqNumber) ⇒
                false
            }
        }) ::
        ((pb: IPB) ⇒ pb.withSnapshotSelectionCriteria(recovery.fromSnapshot)) ::
        ((pb: IPB) ⇒ afterSnapshot.map(onSnapshot ⇒ pb.onSnapshot(bounceOnSnapshot(wrappedCtx.asScala))).getOrElse(pb)) ::
        ((pb: IPB) ⇒ pb.onRecoveryCompleted(internalRecoveryCompleted(wrappedCtx.asScala))) ::
        Nil

    val internal = transformers.foldLeft(rawInternal) { case (pb, xform) ⇒ xform(pb) }

    val middle = Behaviors.setup[RseMiddleCommand] {
      middleCtx ⇒
        /* note: the role of the middle agent is simply to run the "flushintents" thing whenever there is a lull in
        message traffic. it's really there because we can't access the context (to kick new "intent to flush") commands
        as part of the eventHandler.
         */

        val internalRef = middleCtx.spawnAnonymous(internal)
        middleCtx.watch(internalRef)

        Behaviors.receiveMessage[RseMiddleCommand] {
          case cw: CommandWrapper[Command] @unchecked ⇒
            internalRef ! cw
            middleCtx.setReceiveTimeout(0.milliseconds, StartFlushIntents)
            Behaviors.same
          case StartFlushIntents ⇒
            internalRef ! StartFlushIntents
            middleCtx.cancelReceiveTimeout()
            Behaviors.same
        }.receiveSignal {
          case (ctx, t: Terminated) if t.ref == internalRef ⇒
            t.failure.foreach { ex ⇒
              throw ex
            }
            Behaviors.stopped
        }

    }
    val middleRef = wrappedCtx.asScala.spawnAnonymous(middle)
    wrappedCtx.asScala.watch(middleRef)

    val outer =
      Behaviors.receiveMessage[Command] { msg ⇒
        middleRef ! CommandWrapper(msg)
        Behaviors.same
      }
        .receiveSignal {
          case (ctx, t: Terminated) if t.ref == middleRef ⇒
            t.failure.foreach { ex ⇒
              throw ex
            }
            Behaviors.stopped
        }

    outer
  }

  def internalCommandHandler(wrappedCtx: ActorContext[Command])(ctx: ActorContext[RseCommand], state: RseState[State, Notification], command: RseCommand): Effect[RseEvent[Event, Notification], RseState[State, Notification]] =
    command match {
      case cw: CommandWrapper[Command] ⇒
        wrapEffect(commandHandler(wrappedCtx.asScala, state.state, cw.wrapped))

      case StartFlushIntents ⇒
        val eventSeqNrs = state.requestedNotifications.map { case (seqnr, nfy) ⇒ seqnr }

        Effect
          .persist(IntentToNotifyEvents[Event, Notification](eventSeqNrs))
          .thenRun(state ⇒ ctx.self ! ExecuteFlushIntents(eventSeqNrs)) // if we fail at delivering this, we'll find the intents back at recovery time.

      case ExecuteFlushIntents(intentSeqNrs) ⇒
        val intentSeqNrsIndex = intentSeqNrs.toSet
        val selectedNotifications = state.intendedNotifications.filter { case (seqNr, nfy) ⇒ intentSeqNrsIndex.contains(seqNr) }

        if (selectedNotifications.nonEmpty) {
          tryFlushNotifications(ctx, selectedNotifications)(state) /* if we fail at delivering the removals (e.g. we failed while executing the notification) then
          we will find the intent to notify again ('more than once') at recovery time and will restart. */
        }
        Effect.none

      case RemoveIntents(intentSeqNr) if intentSeqNr.isEmpty ⇒
        Effect.none

      case RemoveIntents(intentSeqNr) ⇒
        Effect.persist(CompletedNotificationEvent[Event, Notification](intentSeqNr))
    }

  def internalEventHandler: (RseState[State, Notification], RseEvent[Event, Notification]) ⇒ RseState[State, Notification] = {
    case (state, event: WrappedEvent[Event, Notification]) ⇒ {
      val (innerState, newNotifications) = eventHandler(state.state, event.event)

      val nstate = state.update(innerState).addNotifications(newNotifications)

      if (nstate.requestedNotifications.nonEmpty) {
        /* PROBLEM: we SHOULD be able to send ourself a message saying that we need to flush outgoing notifications! */
        println("foo")
      }
      nstate
    }

    case (state, event: IntentToNotifyEvents[Event, Notification]) ⇒
      state.markIntended(event.notificationNumbers)

    case (state, event: CompletedNotificationEvent[Event, Notification]) ⇒
      state.removeNotifications(event.notificationNumbers)

  }

  def internalRecoveryCompleted(wrappedCtx: ActorContext[Command])(context: ActorContext[RseCommand], state: RseState[State, Notification]): Unit = {

    recoveryCompleted(wrappedCtx, state.state)

    if (state.intendedNotifications.nonEmpty) {
      /* ah, recovering and some intents to notify were not completed? Let's try again (and possibly, fail again. Hail Supervisionà. */
      context.self ! ExecuteFlushIntents(state.intendedNotifications.map { case (nfySeqNr, nfy) ⇒ nfySeqNr })
    }
    if (state.requestedNotifications.nonEmpty) {
      /* ah, recovering and some intents to notify were not started? Let's try again */
      context.self ! StartFlushIntents
    }
  }

  private def bounceOnSnapshot(wrappedCtx: ActorContext[Command])(innerCtw: ActorContext[RseCommand], snapshotMeta: SnapshotMetadata, result: Try[Done]): Unit = {
    afterSnapshot.foreach(callback ⇒ callback(wrappedCtx, snapshotMeta, result))
  }

  override def onSnapshot(callback: (ActorContext[Command], SnapshotMetadata, Try[Done]) ⇒ Unit): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(afterSnapshot = Some(callback))

  override def onRecoveryCompleted(callback: (ActorContext[Command], State) ⇒ Unit): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] = {
    copy(recoveryCompleted = callback)
  }

  override def snapshotWhen(predicate: (State, Event, Long) ⇒ Boolean): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(snapshotCriteria = Some(Right(predicate)))

  override def snapshotEvery(numberOfEvents: Long): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(snapshotCriteria = Some(Left(numberOfEvents)))

  override def withJournalPluginId(id: String): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(journalPluginId = Some(id))

  override def withSnapshotPluginId(id: String): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(snapshotPluginId = Some(id))

  override def withSnapshotSelectionCriteria(selection: SnapshotSelectionCriteria): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(recovery = Recovery(selection))

  override def withTagger(tagger: Event ⇒ Set[String]): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(tagger = tagger)

  override def eventAdapter(adapter: EventAdapter[Event, _]): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(eventAdapter = adapter)

  override def onPersistFailure(backoffStrategy: BackoffSupervisorStrategy): ReliableSecondaryEffectBehavior[Command, Event, State, Notification] =
    copy(supervisionStrategy = backoffStrategy)
}
