/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.fsm

import akka.actor._
import akka.persistence.fsm.PersistentFSM.FSMState
import akka.persistence.serialization.Message
import akka.persistence.{ PersistentActor, RecoveryCompleted, SnapshotOffer }

import scala.annotation.varargs
import scala.collection.immutable
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect.ClassTag

/**
 * A FSM implementation with persistent state.
 *
 * Supports the usual [[akka.actor.FSM]] functionality with additional persistence features.
 * `PersistentFSM` is identified by 'persistenceId' value.
 * State changes are persisted atomically together with domain events, which means that either both succeed or both fail,
 * i.e. a state transition event will not be stored if persistence of an event related to that change fails.
 * Persistence execution order is: persist -&gt; wait for ack -&gt; apply state.
 * Incoming messages are deferred until the state is applied.
 * State Data is constructed based on domain events, according to user's implementation of applyEvent function.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
trait PersistentFSM[S <: FSMState, D, E] extends PersistentActor with PersistentFSMBase[S, D, E] with ActorLogging {
  import akka.persistence.fsm.PersistentFSM._

  /**
   * Enables to pass a ClassTag of a domain event base type from the implementing class
   *
   * @return [[scala.reflect.ClassTag]] of domain event base type
   */
  implicit def domainEventClassTag: ClassTag[E]

  /**
   * Domain event's [[scala.reflect.ClassTag]]
   * Used for identifying domain events during recovery
   */
  val domainEventTag = domainEventClassTag

  /**
   * Map from state identifier to state instance
   */
  lazy val statesMap: Map[String, S] = stateNames.map(name ⇒ (name.identifier, name)).toMap

  /**
   * Timeout set for the current state. Used when saving a snapshot
   */
  private var currentStateTimeout: Option[FiniteDuration] = None

  /**
   * Override this handler to define the action on Domain Event
   *
   * @param domainEvent domain event to apply
   * @param currentData state data of the previous state
   * @return updated state data
   */
  def applyEvent(domainEvent: E, currentData: D): D

  /**
   * Override this handler to define the action on recovery completion
   */
  def onRecoveryCompleted(): Unit = {}

  /**
   * Save the current state as a snapshot
   */
  final def saveStateSnapshot(): Unit = {
    saveSnapshot(PersistentFSMSnapshot(stateName.identifier, stateData, currentStateTimeout))
  }

  /**
   * After recovery events are handled as in usual FSM actor
   */
  override def receiveCommand: Receive = {
    super[PersistentFSMBase].receive
  }

  /**
   * Discover the latest recorded state
   */
  override def receiveRecover: Receive = {
    case domainEventTag(event) ⇒ startWith(stateName, applyEvent(event, stateData))
    case StateChangeEvent(stateIdentifier, timeout) ⇒ startWith(statesMap(stateIdentifier), stateData, timeout)
    case SnapshotOffer(_, PersistentFSMSnapshot(stateIdentifier, data: D, timeout)) ⇒ startWith(statesMap(stateIdentifier), data, timeout)
    case RecoveryCompleted ⇒
      initialize()
      onRecoveryCompleted()
  }

  /**
   * Persist FSM State and FSM State Data
   */
  override private[akka] def applyState(nextState: State): Unit = {
    var eventsToPersist: immutable.Seq[Any] = nextState.domainEvents.toList

    //Prevent StateChangeEvent persistence when staying in the same state, except when state defines a timeout
    if (nextState.notifies || nextState.timeout.nonEmpty) {
      eventsToPersist = eventsToPersist :+ StateChangeEvent(nextState.stateName.identifier, nextState.timeout)
    }

    if (eventsToPersist.isEmpty) {
      //If there are no events to persist, just apply the state
      super.applyState(nextState)
    } else {
      //Persist the events and apply the new state after all event handlers were executed
      var nextData: D = stateData
      var handlersExecutedCounter = 0

      def applyStateOnLastHandler() = {
        handlersExecutedCounter += 1
        if (handlersExecutedCounter == eventsToPersist.size) {
          super.applyState(nextState using nextData)
          currentStateTimeout = nextState.timeout
          nextState.afterTransitionDo(stateData)
        }
      }

      persistAll[Any](eventsToPersist) {
        case domainEventTag(event) ⇒
          nextData = applyEvent(event, nextData)
          applyStateOnLastHandler()
        case StateChangeEvent(stateIdentifier, timeout) ⇒
          applyStateOnLastHandler()
      }
    }
  }
}

object PersistentFSM {
  /**
   * Base persistent event class
   */
  private[persistence] sealed trait PersistentFsmEvent extends Message

  /**
   * Persisted on state change
   *
   * @param stateIdentifier FSM state identifier
   * @param timeout FSM state timeout
   */
  private[persistence] case class StateChangeEvent(stateIdentifier: String, timeout: Option[FiniteDuration]) extends PersistentFsmEvent

  /**
   * FSM state and data snapshot
   *
   * @param stateIdentifier FSM state identifier
   * @param data FSM state data
   * @param timeout FSM state timeout
   * @tparam D state data type
   */
  private[persistence] case class PersistentFSMSnapshot[D](stateIdentifier: String, data: D, timeout: Option[FiniteDuration]) extends Message

  /**
   * FSMState base trait, makes possible for simple default serialization by conversion to String
   */
  trait FSMState {
    def identifier: String
  }

  /**
   * A partial function value which does not match anything and can be used to
   * “reset” `whenUnhandled` and `onTermination` handlers.
   *
   * {{{
   * onTermination(FSM.NullFunction)
   * }}}
   */
  object NullFunction extends PartialFunction[Any, Nothing] {
    def isDefinedAt(o: Any) = false
    def apply(o: Any) = sys.error("undefined")
  }

  /**
   * Message type which is sent directly to the subscribed actor in
   * [[akka.actor.FSM.SubscribeTransitionCallBack]] before sending any
   * [[akka.actor.FSM.Transition]] messages.
   */
  final case class CurrentState[S](fsmRef: ActorRef, state: S, timeout: Option[FiniteDuration])

  /**
   * Message type which is used to communicate transitions between states to
   * all subscribed listeners (use [[akka.actor.FSM.SubscribeTransitionCallBack]]).
   */
  final case class Transition[S](fsmRef: ActorRef, from: S, to: S, timeout: Option[FiniteDuration])

  /**
   * Send this to an [[akka.actor.FSM]] to request first the [[PersistentFSM.CurrentState]]
   * and then a series of [[PersistentFSM.Transition]] updates. Cancel the subscription
   * using [[PersistentFSM.UnsubscribeTransitionCallBack]].
   */
  final case class SubscribeTransitionCallBack(actorRef: ActorRef)

  /**
   * Unsubscribe from [[akka.actor.FSM.Transition]] notifications which was
   * effected by sending the corresponding [[akka.actor.FSM.SubscribeTransitionCallBack]].
   */
  final case class UnsubscribeTransitionCallBack(actorRef: ActorRef)

  /**
   * Reason why this [[akka.actor.FSM]] is shutting down.
   */
  sealed trait Reason

  /**
   * Default reason if calling `stop()`.
   */
  case object Normal extends Reason

  /**
   * Reason given when someone was calling `system.stop(fsm)` from outside;
   * also applies to `Stop` supervision directive.
   */
  case object Shutdown extends Reason

  /**
   * Signifies that the [[akka.actor.FSM]] is shutting itself down because of
   * an error, e.g. if the state to transition into does not exist. You can use
   * this to communicate a more precise cause to the `onTermination` block.
   */
  final case class Failure(cause: Any) extends Reason

  /**
   * This case object is received in case of a state timeout.
   */
  case object StateTimeout

  /** INTERNAL API */
  private[persistence] final case class TimeoutMarker(generation: Long)

  /**
   * INTERNAL API
   */
  // FIXME: what about the cancellable?
  private[persistence] final case class Timer(name: String, msg: Any, repeat: Boolean, generation: Int)(context: ActorContext)
    extends NoSerializationVerificationNeeded {
    private var ref: Option[Cancellable] = _
    private val scheduler = context.system.scheduler
    private implicit val executionContext = context.dispatcher

    def schedule(actor: ActorRef, timeout: FiniteDuration): Unit =
      ref = Some(
        if (repeat) scheduler.schedule(timeout, timeout, actor, this)
        else scheduler.scheduleOnce(timeout, actor, this))

    def cancel(): Unit =
      if (ref.isDefined) {
        ref.get.cancel()
        ref = None
      }
  }

  /**
   * This extractor is just convenience for matching a (S, S) pair, including a
   * reminder what the new state is.
   */
  object `->` {
    def unapply[S](in: (S, S)) = Some(in)
  }
  val `→` = `->`

  /**
   * Log Entry of the [[akka.actor.LoggingFSM]], can be obtained by calling `getLog`.
   */
  final case class LogEntry[S, D](stateName: S, stateData: D, event: Any)

  /**
   * This captures all of the managed state of the [[akka.actor.FSM]]: the state
   * name, the state data, possibly custom timeout, stop reason, replies
   * accumulated while processing the last message, possibly domain event and handler
   * to be executed after FSM moves to the new state (also triggered when staying in the same state)
   */
  final case class State[S, D, E](
    stateName:         S,
    stateData:         D,
    timeout:           Option[FiniteDuration] = None,
    stopReason:        Option[Reason]         = None,
    replies:           List[Any]              = Nil,
    domainEvents:      Seq[E]                 = Nil,
    afterTransitionDo: D ⇒ Unit               = { _: D ⇒ })(private[akka] val notifies: Boolean = true) {

    /**
     * Copy object and update values if needed.
     */
    private[akka] def copy(stateName: S = stateName, stateData: D = stateData, timeout: Option[FiniteDuration] = timeout, stopReason: Option[Reason] = stopReason, replies: List[Any] = replies, notifies: Boolean = notifies, domainEvents: Seq[E] = domainEvents, afterTransitionDo: D ⇒ Unit = afterTransitionDo): State[S, D, E] = {
      State(stateName, stateData, timeout, stopReason, replies, domainEvents, afterTransitionDo)(notifies)
    }

    /**
     * Modify state transition descriptor to include a state timeout for the
     * next state. This timeout overrides any default timeout set for the next
     * state.
     *
     * Use Duration.Inf to deactivate an existing timeout.
     */
    def forMax(timeout: Duration): State[S, D, E] = timeout match {
      case f: FiniteDuration ⇒ copy(timeout = Some(f))
      case _                 ⇒ copy(timeout = None)
    }

    /**
     * Send reply to sender of the current message, if available.
     *
     * @return this state transition descriptor
     */
    def replying(replyValue: Any): State[S, D, E] = {
      copy(replies = replyValue :: replies)
    }

    /**
     * Modify state transition descriptor with new state data. The data will be
     * set when transitioning to the new state.
     */
    private[akka] def using(@deprecatedName('nextStateDate) nextStateData: D): State[S, D, E] = {
      copy(stateData = nextStateData)
    }

    /**
     * INTERNAL API.
     */
    private[akka] def withStopReason(reason: Reason): State[S, D, E] = {
      copy(stopReason = Some(reason))
    }

    private[akka] def withNotification(notifies: Boolean): State[S, D, E] = {
      copy(notifies = notifies)
    }

    /**
     * Specify domain events to be applied when transitioning to the new state.
     */
    @varargs def applying(events: E*): State[S, D, E] = {
      copy(domainEvents = domainEvents ++ events)
    }

    /**
     * Register a handler to be triggered after the state has been persisted successfully
     */
    def andThen(handler: D ⇒ Unit): State[S, D, E] = {
      copy(afterTransitionDo = handler)
    }
  }

  /**
   * All messages sent to the [[akka.actor.FSM]] will be wrapped inside an
   * `Event`, which allows pattern matching to extract both state and data.
   */
  final case class Event[D](event: Any, stateData: D) extends NoSerializationVerificationNeeded

  /**
   * Case class representing the state of the [[akka.actor.FSM]] whithin the
   * `onTermination` block.
   */
  final case class StopEvent[S, D](reason: Reason, currentState: S, stateData: D) extends NoSerializationVerificationNeeded

}

/**
 * Java API: compatible with lambda expressions
 *
 * Persistent Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractPersistentFSM[S <: FSMState, D, E] extends AbstractPersistentFSMBase[S, D, E] with PersistentFSM[S, D, E] {
  import java.util.function.Consumer

  /**
   * Adapter from Java 8 Functional Interface to Scala Function
   * @param action - Java 8 lambda expression defining the action
   * @return action represented as a Scala Functin
   */
  final def exec(action: Consumer[D]): D ⇒ Unit =
    data ⇒ action.accept(data)

  /**
   * Adapter from Java [[Class]] to [[scala.reflect.ClassTag]]
   * @return domain event [[scala.reflect.ClassTag]]
   */
  final override def domainEventClassTag: ClassTag[E] =
    ClassTag(domainEventClass)

  /**
   * Domain event's [[Class]]
   * Used for identifying domain events during recovery
   */
  def domainEventClass: Class[E]
}

/**
 * Java API: compatible with lambda expressions
 *
 * Persistent Finite State Machine actor abstract base class with FSM Logging
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractPersistentLoggingFSM[S <: FSMState, D, E]
  extends AbstractPersistentFSMBase[S, D, E]
  with LoggingPersistentFSM[S, D, E]
  with PersistentFSM[S, D, E]
