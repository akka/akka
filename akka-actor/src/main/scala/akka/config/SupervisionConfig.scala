/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.dispatch.MessageDispatcher
import akka.actor.{ Terminated, ActorRef }
import akka.japi.{ Procedure2 }

case class RemoteAddress(val hostname: String, val port: Int)

/**
 * Configuration classes - not to be used as messages.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Supervision {
  sealed abstract class ConfigElement

  abstract class Server extends ConfigElement
  sealed abstract class LifeCycle extends ConfigElement
  sealed abstract class FaultHandlingStrategy(val trapExit: List[Class[_ <: Throwable]], val lifeCycle: LifeCycle) extends ConfigElement

  case class SupervisorConfig(restartStrategy: FaultHandlingStrategy, worker: List[Server], maxRestartsHandler: (ActorRef, Terminated) ⇒ Unit = { (aRef, max) ⇒ () }) extends Server {
    //Java API
    def this(restartStrategy: FaultHandlingStrategy, worker: Array[Server]) = this(restartStrategy, worker.toList)
    def this(restartStrategy: FaultHandlingStrategy, worker: Array[Server], restartHandler: Procedure2[ActorRef, Terminated]) = this(restartStrategy, worker.toList, { (aRef, max) ⇒ restartHandler.apply(aRef, max) })
  }

  class Supervise(val actorRef: ActorRef, val lifeCycle: LifeCycle, val registerAsRemoteService: Boolean = false) extends Server {
    //Java API
    def this(actorRef: ActorRef, lifeCycle: LifeCycle) =
      this(actorRef, lifeCycle, false)
  }

  object Supervise {
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle, registerAsRemoteService: Boolean = false) = new Supervise(actorRef, lifeCycle, registerAsRemoteService)
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle) = new Supervise(actorRef, lifeCycle, false)
    def unapply(supervise: Supervise) = Some((supervise.actorRef, supervise.lifeCycle, supervise.registerAsRemoteService))
  }

  object AllForOnePermanentStrategy {
    def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): AllForOnePermanentStrategy =
      new AllForOnePermanentStrategy(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  /**
   * Restart all actors linked to the same supervisor when one fails,
   * trapExit = which Throwables should be intercepted
   * maxNrOfRetries = the number of times an actor is allowed to be restarted
   * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
   */
  case class AllForOnePermanentStrategy(override val trapExit: List[Class[_ <: Throwable]],
                                        maxNrOfRetries: Option[Int] = None,
                                        withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy(trapExit, Permanent) {
    def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toList,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  case class AllForOneTemporaryStrategy(override val trapExit: List[Class[_ <: Throwable]]) extends FaultHandlingStrategy(trapExit, Temporary)

  object OneForOnePermanentStrategy {
    def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int): OneForOnePermanentStrategy =
      new OneForOnePermanentStrategy(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  /**
   * Restart an actor when it fails
   * trapExit = which Throwables should be intercepted
   * maxNrOfRetries = the number of times an actor is allowed to be restarted
   * withinTimeRange = millisecond time window for maxNrOfRetries, negative means no window
   */
  case class OneForOnePermanentStrategy(override val trapExit: List[Class[_ <: Throwable]],
                                        maxNrOfRetries: Option[Int] = None,
                                        withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy(trapExit, Permanent) {
    def this(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: Array[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toList,
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))

    def this(trapExit: java.util.List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
      this(trapExit.toArray.toList.asInstanceOf[List[Class[_ <: Throwable]]],
        if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  }

  case class OneForOneTemporaryStrategy(override val trapExit: List[Class[_ <: Throwable]]) extends FaultHandlingStrategy(trapExit, Temporary)

  //Scala API
  case object Permanent extends LifeCycle
  case object Temporary extends LifeCycle

  //Java API (& Scala if you fancy)
  def permanent(): LifeCycle = Permanent
  def temporary(): LifeCycle = Temporary

  case class SuperviseTypedActor(_intf: Class[_],
                                 val target: Class[_],
                                 val lifeCycle: LifeCycle,
                                 val timeout: Long,
                                 _dispatcher: MessageDispatcher // optional
                                 ) extends Server {
    val intf: Option[Class[_]] = Option(_intf)
    val dispatcher: Option[MessageDispatcher] = Option(_dispatcher)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(null: Class[_], target, lifeCycle, timeout, null: MessageDispatcher)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(intf, target, lifeCycle, timeout, null: MessageDispatcher)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher) =
      this(null: Class[_], target, lifeCycle, timeout, dispatcher)
  }
}
