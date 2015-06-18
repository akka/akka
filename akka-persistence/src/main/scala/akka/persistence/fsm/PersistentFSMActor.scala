/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.fsm

import akka.actor.ActorLogging
import akka.persistence.fsm.PersistentFsmActor.FSMState
import akka.persistence.serialization.Message
import akka.persistence.{ PersistentActor, RecoveryCompleted }

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
 * FSM actor implementation with persistent state
 *
 * Supports the usual [[akka.actor.FSM]] functionality with additional persistence features.
 * State and State Data are persisted on every state change.
 * FSM is identified by 'persistenceId' value.
 * Persistence execution order is: persist -&gt; wait for ack -&gt; apply state. Incoming messages are deferred until the state is applied.
 */
trait PersistentFsmActor[S <: FSMState, D, E] extends PersistentActor with FSM[S, D, E] with ActorLogging {
  import akka.persistence.fsm.PersistentFsmActor._

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
   * After recovery events are handled as in usual FSM actor
   */
  override def receiveCommand: Receive = {
    super[FSM].receive
  }

  /**
   * Discover the latest recorded state
   */
  override def receiveRecover: Receive = {
    case domainEventTag(event)                      ⇒ startWith(stateName, applyEvent(event, stateData))
    case StateChangeEvent(stateIdentifier, timeout) ⇒ startWith(statesMap(stateIdentifier), stateData, timeout)
    case RecoveryCompleted ⇒
      initialize()
      onRecoveryCompleted()
  }

  /**
   * Persist FSM State and FSM State Data
   */
  override private[akka] def applyState(nextState: State): Unit = {
    val eventsToPersist: immutable.Seq[Any] = nextState.domainEvents.toList :+ StateChangeEvent(nextState.stateName.identifier, nextState.timeout)
    var nextData: D = stateData
    persistAll[Any](eventsToPersist) {
      case domainEventTag(event) ⇒
        nextData = applyEvent(event, nextData)
      case StateChangeEvent(stateIdentifier, timeout) ⇒
        super.applyState(nextState using nextData)
        nextState.afterTransitionDo(stateData)
    }
  }
}

object PersistentFsmActor {
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
   * FSMState base trait, makes possible for simple default serialization by conversion to String
   */
  trait FSMState {
    def identifier: String
  }
}

/**
 * Java API: compatible with lambda expressions
 *
 * Persistent Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractPersistentFsmActor[S <: FSMState, D, E] extends AbstractFSM[S, D, E] with PersistentFsmActor[S, D, E] {
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
abstract class AbstractPersistentLoggingFsmActor[S <: FSMState, D, E] extends AbstractLoggingFSM[S, D, E] with PersistentFsmActor[S, D, E]
