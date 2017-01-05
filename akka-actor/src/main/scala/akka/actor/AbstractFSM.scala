/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import scala.concurrent.duration.FiniteDuration

/**
 * Java API: compatible with lambda expressions
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
object AbstractFSM {
  /**
   * A partial function value which does not match anything and can be used to
   * “reset” `whenUnhandled` and `onTermination` handlers.
   *
   * {{{
   * onTermination(FSM.NullFunction())
   * }}}
   */
  def NullFunction[S, D]: PartialFunction[S, D] = FSM.NullFunction
}

/**
 * Java API: compatible with lambda expressions
 *
 * Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractFSM[S, D] extends FSM[S, D] {
  import akka.japi.pf._
  import akka.japi.pf.FI._
  import java.util.{ List ⇒ JList }
  import FSM._

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state.
   *
   * @param stateName designator for the state
   * @param stateFunction partial function describing response to input
   */
  final def when(stateName: S)(stateFunction: StateFunction): Unit =
    when(stateName, null: FiniteDuration)(stateFunction)

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state.
   *
   * @param stateName designator for the state
   * @param stateFunctionBuilder partial function builder describing response to input
   */
  final def when(stateName: S, stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    when(stateName, null, stateFunctionBuilder)

  /**
   * Insert a new StateFunction at the end of the processing chain for the
   * given state. If the stateTimeout parameter is set, entering this state
   * without a differing explicit timeout setting will trigger a StateTimeout
   * event; the same is true when using #stay.
   *
   * @param stateName designator for the state
   * @param stateTimeout default state timeout for this state
   * @param stateFunctionBuilder partial function builder describing response to input
   */
  final def when(
    stateName:            S,
    stateTimeout:         FiniteDuration,
    stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    when(stateName, stateTimeout)(stateFunctionBuilder.build())

  /**
   * Set initial state. Call this method from the constructor before the [[#initialize]] method.
   * If different state is needed after a restart this method, followed by [[#initialize]], can
   * be used in the actor life cycle hooks [[akka.actor.Actor#preStart]] and [[akka.actor.Actor#postRestart]].
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   */
  final def startWith(stateName: S, stateData: D): Unit =
    startWith(stateName, stateData, null: FiniteDuration)

  /**
   * Set initial state. Call this method from the constructor before the [[#initialize]] method.
   * If different state is needed after a restart this method, followed by [[#initialize]], can
   * be used in the actor life cycle hooks [[akka.actor.Actor#preStart]] and [[akka.actor.Actor#postRestart]].
   *
   * @param stateName initial state designator
   * @param stateData initial state data
   * @param timeout state timeout for the initial state, overriding the default timeout for that state
   */
  final def startWith(stateName: S, stateData: D, timeout: FiniteDuration): Unit =
    startWith(stateName, stateData, Option(timeout))

  /**
   * Add a handler which is called upon each state transition, i.e. not when
   * staying in the same state.
   *
   * <b>Multiple handlers may be installed, and every one of them will be
   * called, not only the first one matching.</b>
   */
  final def onTransition(transitionHandlerBuilder: FSMTransitionHandlerBuilder[S]): Unit =
    onTransition(transitionHandlerBuilder.build().asInstanceOf[TransitionHandler])

  /**
   * Add a handler which is called upon each state transition, i.e. not when
   * staying in the same state.
   *
   * <b>Multiple handlers may be installed, and every one of them will be
   * called, not only the first one matching.</b>
   */
  final def onTransition(transitionHandler: UnitApply2[S, S]): Unit =
    onTransition(transitionHandler(_: S, _: S))

  /**
   * Set handler which is called upon reception of unhandled messages. Calling
   * this method again will overwrite the previous contents.
   *
   * The current state may be queried using ``stateName``.
   */
  final def whenUnhandled(stateFunctionBuilder: FSMStateFunctionBuilder[S, D]): Unit =
    whenUnhandled(stateFunctionBuilder.build())

  /**
   * Set handler which is called upon termination of this FSM actor. Calling
   * this method again will overwrite the previous contents.
   */
  final def onTermination(stopBuilder: FSMStopBuilder[S, D]): Unit =
    onTermination(stopBuilder.build().asInstanceOf[PartialFunction[StopEvent, Unit]])

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on an event and data type and a predicate.
   *
   * @param eventType  the event type to match on
   * @param dataType  the data type to match on
   * @param predicate  a predicate to evaluate on the matched types
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET, DT <: D](eventType: Class[ET], dataType: Class[DT], predicate: TypedPredicate2[ET, DT], apply: Apply2[ET, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, dataType, predicate, apply)

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on an event and data type.
   *
   * @param eventType  the event type to match on
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET, DT <: D](eventType: Class[ET], dataType: Class[DT], apply: Apply2[ET, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, dataType, apply)

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event type and predicate matches.
   *
   * @param eventType  the event type to match on
   * @param predicate  a predicate that will be evaluated on the data and the event
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET](eventType: Class[ET], predicate: TypedPredicate2[ET, D], apply: Apply2[ET, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, predicate, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event type matches.
   *
   * @param eventType  the event type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[ET](eventType: Class[ET], apply: Apply2[ET, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the predicate matches.
   *
   * @param predicate  a predicate that will be evaluated on the data and the event
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent(predicate: TypedPredicate2[AnyRef, D], apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(predicate, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on the data type and if any of the event types
   * in the list match or any of the event instances in the list compares equal.
   *
   * @param eventMatches  a list of types or instances to match against
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent[DT <: D](eventMatches: JList[AnyRef], dataType: Class[DT], apply: Apply2[AnyRef, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventMatches, dataType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if any of the event types in the list match or any
   * of the event instances in the list compares equal.
   *
   * @param eventMatches  a list of types or instances to match against
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEvent(eventMatches: JList[AnyRef], apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().event(eventMatches, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on the data type and if the event compares equal.
   *
   * @param event  an event to compare equal against
   * @param dataType  the data type to match on
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEventEquals[E, DT <: D](event: E, dataType: Class[DT], apply: Apply2[E, DT, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().eventEquals(event, dataType, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches if the event compares equal.
   *
   * @param event  an event to compare equal against
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchEventEquals[E](event: E, apply: Apply2[E, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().eventEquals(event, apply);

  /**
   * Create an [[akka.japi.pf.FSMStateFunctionBuilder]] with the first case statement set.
   *
   * A case statement that matches on any type of event.
   *
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchAnyEvent(apply: Apply2[AnyRef, D, State]): FSMStateFunctionBuilder[S, D] =
    new FSMStateFunctionBuilder[S, D]().anyEvent(apply)

  /**
   * Create an [[akka.japi.pf.FSMTransitionHandlerBuilder]] with the first case statement set.
   *
   * A case statement that matches on a from state and a to state.
   *
   * @param fromState  the from state to match on
   * @param toState  the to state to match on
   * @param apply  an action to apply when the states match
   * @return the builder with the case statement added
   */
  final def matchState(fromState: S, toState: S, apply: UnitApplyVoid): FSMTransitionHandlerBuilder[S] =
    new FSMTransitionHandlerBuilder[S]().state(fromState, toState, apply)

  /**
   * Create an [[akka.japi.pf.FSMTransitionHandlerBuilder]] with the first case statement set.
   *
   * A case statement that matches on a from state and a to state.
   *
   * @param fromState  the from state to match on
   * @param toState  the to state to match on
   * @param apply  an action to apply when the states match
   * @return the builder with the case statement added
   */
  final def matchState(fromState: S, toState: S, apply: UnitApply2[S, S]): FSMTransitionHandlerBuilder[S] =
    new FSMTransitionHandlerBuilder[S]().state(fromState, toState, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on an [[FSM.Reason]].
   *
   * @param reason  the reason for the termination
   * @param apply  an action to apply to the event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchStop(reason: Reason, apply: UnitApply2[S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reason, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on a reason type.
   *
   * @param reasonType  the reason type to match on
   * @param apply  an action to apply to the reason, event and state data if there is a match
   * @return the builder with the case statement added
   */
  final def matchStop[RT <: Reason](reasonType: Class[RT], apply: UnitApply3[RT, S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reasonType, apply)

  /**
   * Create an [[akka.japi.pf.FSMStopBuilder]] with the first case statement set.
   *
   * A case statement that matches on a reason type and a predicate.
   *
   * @param reasonType  the reason type to match on
   * @param apply  an action to apply to the reason, event and state data if there is a match
   * @param predicate  a predicate that will be evaluated on the reason if the type matches
   * @return the builder with the case statement added
   */
  final def matchStop[RT <: Reason](reasonType: Class[RT], predicate: TypedPredicate[RT], apply: UnitApply3[RT, S, D]): FSMStopBuilder[S, D] =
    new FSMStopBuilder[S, D]().stop(reasonType, predicate, apply)

  /**
   * Create a [[akka.japi.pf.UnitPFBuilder]] with the first case statement set.
   *
   * @param dataType  a type to match the argument against
   * @param apply  an action to apply to the argument if the type matches
   * @return a builder with the case statement added
   */
  final def matchData[DT <: D](dataType: Class[DT], apply: UnitApply[DT]): UnitPFBuilder[D] =
    UnitMatch.`match`(dataType, apply)

  /**
   * Create a [[akka.japi.pf.UnitPFBuilder]] with the first case statement set.
   *
   * @param dataType  a type to match the argument against
   * @param predicate  a predicate that will be evaluated on the argument if the type matches
   * @param apply  an action to apply to the argument if the type and predicate matches
   * @return a builder with the case statement added
   */
  final def matchData[DT <: D](dataType: Class[DT], predicate: TypedPredicate[DT], apply: UnitApply[DT]): UnitPFBuilder[D] =
    UnitMatch.`match`(dataType, predicate, apply)

  /**
   * Produce transition to other state. Return this from a state function in
   * order to effect the transition.
   *
   * @param nextStateName state designator for the next state
   * @return state transition descriptor
   */
  final def goTo(nextStateName: S): State = goto(nextStateName)

  /**
   * Schedule named timer to deliver message after given delay, possibly repeating.
   * Any existing timer with the same name will automatically be canceled before
   * adding the new timer.
   * @param name identifier to be used with cancelTimer()
   * @param msg message to be delivered
   * @param timeout delay of first message delivery and between subsequent messages
   */
  final def setTimer(name: String, msg: Any, timeout: FiniteDuration): Unit =
    setTimer(name, msg, timeout, false)

  /**
   * Default reason if calling `stop()`.
   */
  val Normal: FSM.Reason = FSM.Normal

  /**
   * Reason given when someone was calling `system.stop(fsm)` from outside;
   * also applies to `Stop` supervision directive.
   */
  val Shutdown: FSM.Reason = FSM.Shutdown
}

/**
 * Java API: compatible with lambda expressions
 *
 * Finite State Machine actor abstract base class.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractLoggingFSM[S, D] extends AbstractFSM[S, D] with LoggingFSM[S, D]

/**
 * Java API: compatible with lambda expressions
 *
 * Finite State Machine actor abstract base class with Stash support.
 *
 * This is an EXPERIMENTAL feature and is subject to change until it has received more real world testing.
 */
abstract class AbstractFSMWithStash[S, D] extends AbstractFSM[S, D] with Stash
