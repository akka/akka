/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.testkit

import akka.actor._
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
 * This is a specialized form of the TestActorRef with support for querying and
 * setting the state of a FSM. Use a LoggingFSM with this class if you also
 * need to inspect event traces.
 *
 * <pre><code>
 * val fsm = TestFSMRef(new Actor with LoggingFSM[Int, Null] {
 *     override def logDepth = 12
 *     startWith(1, null)
 *     when(1) {
 *       case Ev("hello") =&gt; goto(2)
 *     }
 *     when(2) {
 *       case Ev("world") =&gt; goto(1)
 *     }
 *   }
 * assert (fsm.stateName == 1)
 * fsm ! "hallo"
 * assert (fsm.stateName == 2)
 * assert (fsm.underlyingActor.getLog == IndexedSeq(FSMLogEntry(1, null, "hallo")))
 * </code></pre>
 *
 * @since 1.2
 */
class TestFSMRef[S, D, T <: Actor](
  system: ActorSystem,
  props: Props,
  supervisor: ActorRef,
  name: String)(implicit ev: T <:< FSM[S, D])
  extends TestActorRef[T](system, props, supervisor, name) {

  private def fsm: T = underlyingActor

  /**
   * Get current state name of this FSM.
   */
  def stateName: S = fsm.stateName

  /**
   * Get current state data of this FSM.
   */
  def stateData: D = fsm.stateData

  /**
   * Change FSM state; any value left out defaults to the current FSM state
   * (timeout defaults to None). This method is directly equivalent to a
   * corresponding transition initiated from within the FSM, including timeout
   * and stop handling.
   */
  def setState(stateName: S = fsm.stateName, stateData: D = fsm.stateData, timeout: FiniteDuration = null, stopReason: Option[FSM.Reason] = None) {
    fsm.applyState(FSM.State(stateName, stateData, Option(timeout), stopReason))
  }

  /**
   * Proxy for [[akka.actor.FSM#setTimer]].
   */
  def setTimer(name: String, msg: Any, timeout: FiniteDuration, repeat: Boolean = false) {
    fsm.setTimer(name, msg, timeout, repeat)
  }

  /**
   * Proxy for [[akka.actor.FSM#cancelTimer]].
   */
  def cancelTimer(name: String) { fsm.cancelTimer(name) }

  /**
   * Proxy for [[akka.actor.FSM#isStateTimerActive]].
   */
  def isTimerActive(name: String) = fsm.isTimerActive(name)

  /**
   * Proxy for [[akka.actor.FSM#isStateTimerActive]].
   */
  def isStateTimerActive = fsm.isStateTimerActive
}

object TestFSMRef {

  def apply[S, D, T <: Actor: ClassTag](factory: ⇒ T)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl]
    new TestFSMRef(impl, Props(factory), impl.guardian.asInstanceOf[InternalActorRef], TestActorRef.randomName)
  }

  def apply[S, D, T <: Actor: ClassTag](factory: ⇒ T, name: String)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl]
    new TestFSMRef(impl, Props(factory), impl.guardian.asInstanceOf[InternalActorRef], name)
  }

  def apply[S, D, T <: Actor: ClassTag](factory: ⇒ T, supervisor: ActorRef, name: String)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl]
    new TestFSMRef(impl, Props(factory), supervisor.asInstanceOf[InternalActorRef], name)
  }

  def apply[S, D, T <: Actor: ClassTag](factory: ⇒ T, supervisor: ActorRef)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl]
    new TestFSMRef(impl, Props(factory), supervisor.asInstanceOf[InternalActorRef], TestActorRef.randomName)
  }
}
