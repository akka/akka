/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import se.scalablesolutions.akka.actor.{ActorRef}
import se.scalablesolutions.akka.dispatch.MessageDispatcher

sealed abstract class FaultHandlingStrategy {
  def trapExit: List[Class[_ <: Throwable]]
}

object AllForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    new AllForOneStrategy(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def apply(trapExit: Array[Class[Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    new AllForOneStrategy(trapExit.toList,maxNrOfRetries,withinTimeRange)
}

case class AllForOneStrategy(trapExit: List[Class[_ <: Throwable]],
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {
  def this(trapExit: List[Class[_ <: Throwable]],maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def this(trapExit: Array[Class[Throwable]],maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toList,maxNrOfRetries,withinTimeRange)
}

object OneForOneStrategy {
  def apply(trapExit: List[Class[_ <: Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    new OneForOneStrategy(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def apply(trapExit: Array[Class[Throwable]], maxNrOfRetries: Int, withinTimeRange: Int) =
    new OneForOneStrategy(trapExit.toList,maxNrOfRetries,withinTimeRange)
}

case class OneForOneStrategy(trapExit: List[Class[_ <: Throwable]],
                             maxNrOfRetries: Option[Int] = None,
                             withinTimeRange: Option[Int] = None) extends FaultHandlingStrategy {
  def this(trapExit: List[Class[_ <: Throwable]],maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit, if (maxNrOfRetries < 0) None else Some(maxNrOfRetries), if (withinTimeRange < 0) None else Some(withinTimeRange))
  def this(trapExit: Array[Class[Throwable]],maxNrOfRetries: Int, withinTimeRange: Int) =
    this(trapExit.toList,maxNrOfRetries,withinTimeRange)
}

case object NoFaultHandlingStrategy extends FaultHandlingStrategy {
  def trapExit: List[Class[_ <: Throwable]] = Nil
}

sealed abstract class LifeCycle

case object Permanent extends LifeCycle
case object Temporary extends LifeCycle
case object UndefinedLifeCycle extends LifeCycle

case class RemoteAddress(val hostname: String, val port: Int)

/**
 * Configuration classes - not to be used as messages.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Supervision {
  sealed abstract class ConfigElement

  abstract class Server extends ConfigElement
  abstract class FailOverScheme extends ConfigElement

  case class SupervisorConfig(restartStrategy: RestartStrategy, worker: List[Server]) extends Server {
  //Java API
    def this(restartStrategy: RestartStrategy, worker: Array[Server]) = this(restartStrategy,worker.toList)
  }

  class Supervise(val actorRef: ActorRef, val lifeCycle: LifeCycle, _remoteAddress: RemoteAddress) extends Server {
    val remoteAddress: Option[RemoteAddress] = Option(_remoteAddress)
  }
  
  object Supervise {
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle, remoteAddress: RemoteAddress) = new Supervise(actorRef, lifeCycle, remoteAddress)
    def apply(actorRef: ActorRef, lifeCycle: LifeCycle) = new Supervise(actorRef, lifeCycle, null)
    def unapply(supervise: Supervise) = Some((supervise.actorRef, supervise.lifeCycle, supervise.remoteAddress))
  }

  case class RestartStrategy(scheme: FailOverScheme, maxNrOfRetries: Int, withinTimeRange: Int, trapExceptions: Array[Class[_ <: Throwable]]) extends ConfigElement

  object RestartStrategy {
     def apply(scheme: FailOverScheme, maxNrOfRetries: Int, withinTimeRange: Int, trapExceptions: List[Class[_ <: Throwable]]) =
       new RestartStrategy(scheme,maxNrOfRetries,withinTimeRange,trapExceptions.toArray)
  }

  //Java API
  class AllForOne extends FailOverScheme
  class OneForOne extends FailOverScheme

  //Java API (& Scala if you fancy)
  def permanent() = se.scalablesolutions.akka.config.Permanent
  def temporary() = se.scalablesolutions.akka.config.Temporary
  def undefinedLifeCycle = se.scalablesolutions.akka.config.UndefinedLifeCycle

  //Scala API
  object AllForOne extends AllForOne { def apply() = this }
  object OneForOne extends OneForOne { def apply() = this }

  case class SuperviseTypedActor(_intf: Class[_],
                  val target: Class[_],
                  val lifeCycle: LifeCycle,
                  val timeout: Long,
                  val transactionRequired: Boolean,
                  _dispatcher: MessageDispatcher, // optional
                  _remoteAddress: RemoteAddress   // optional
          ) extends Server {
    val intf: Option[Class[_]] = Option(_intf)
    val dispatcher: Option[MessageDispatcher] = Option(_dispatcher)
    val remoteAddress: Option[RemoteAddress] = Option(_remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(null: Class[_], target, lifeCycle, timeout, false, null.asInstanceOf[MessageDispatcher], null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long) =
      this(intf, target, lifeCycle, timeout, false, null.asInstanceOf[MessageDispatcher], null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher) =
      this(intf, target, lifeCycle, timeout, false, dispatcher, null)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher) =
      this(null: Class[_], target, lifeCycle, timeout, false, dispatcher, null:RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, false, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, false, null, remoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, false, dispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, false, dispatcher, remoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean) =
      this(intf, target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, null: RemoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher) =
      this(intf, target, lifeCycle, timeout, transactionRequired, dispatcher, null: RemoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, dispatcher, null: RemoteAddress)

    def this(intf: Class[_], target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, remoteAddress: RemoteAddress) =
      this(intf, target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, null: MessageDispatcher, remoteAddress)

    def this(target: Class[_], lifeCycle: LifeCycle, timeout: Long, transactionRequired: Boolean, dispatcher: MessageDispatcher, remoteAddress: RemoteAddress) =
      this(null: Class[_], target, lifeCycle, timeout, transactionRequired, dispatcher, remoteAddress)
  }
}