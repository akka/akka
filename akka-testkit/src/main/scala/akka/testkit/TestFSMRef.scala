/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.actor._
import scala.concurrent.util.Duration
import akka.dispatch.DispatcherPrerequisites

/**
 * This is a specialised form of the TestActorRef with support for querying and
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
 * @author Roland Kuhn
 * @since 1.2
 */
class TestFSMRef[S, D, T <: Actor](
  system: ActorSystemImpl,
  _prerequisites: DispatcherPrerequisites,
  props: Props,
  supervisor: InternalActorRef,
  name: String)(implicit ev: T <:< FSM[S, D])
  extends TestActorRef(system, _prerequisites, props, supervisor, name) {

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
  def setState(stateName: S = fsm.stateName, stateData: D = fsm.stateData, timeout: Duration = null, stopReason: Option[FSM.Reason] = None) {
    fsm.applyState(FSM.State(stateName, stateData, Option(timeout), stopReason))
  }

  /**
   * Proxy for FSM.setTimer.
   */
  def setTimer(name: String, msg: Any, timeout: Duration, repeat: Boolean) {
    fsm.setTimer(name, msg, timeout, repeat)
  }

  /**
   * Proxy for FSM.cancelTimer.
   */
  def cancelTimer(name: String) { fsm.cancelTimer(name) }

  /**
   * Proxy for FSM.timerActive_?.
   */
  def timerActive_?(name: String) = fsm.timerActive_?(name)

}

object TestFSMRef {

  def apply[S, D, T <: Actor](factory: ⇒ T)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
    new TestFSMRef(impl, system.dispatchers.prerequisites, Props(creator = () ⇒ factory), impl.guardian.asInstanceOf[InternalActorRef], TestActorRef.randomName)
  }

  def apply[S, D, T <: Actor](factory: ⇒ T, name: String)(implicit ev: T <:< FSM[S, D], system: ActorSystem): TestFSMRef[S, D, T] = {
    val impl = system.asInstanceOf[ActorSystemImpl] //TODO ticket #1559
    new TestFSMRef(impl, system.dispatchers.prerequisites, Props(creator = () ⇒ factory), impl.guardian.asInstanceOf[InternalActorRef], name)
  }
}
