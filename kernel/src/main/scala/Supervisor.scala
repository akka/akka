/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable.HashMap

import se.scalablesolutions.akka.kernel.Helpers._

import se.scalablesolutions.akka.kernel.config.ScalaConfig._

/**
 * Messages that the supervisor responds to and returns.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed abstract class SupervisorMessage
case object Start extends SupervisorMessage
case object Stop extends SupervisorMessage
case class Configure(config: SupervisorConfig, factory: SupervisorFactory) extends SupervisorMessage

/**
 * Abstract base class for all supervisor factories.
 * <p>
 * Example usage:
 * <pre>
 *  class MySupervisorFactory extends SupervisorFactory {
 *
 *    override protected def getSupervisorConfig: SupervisorConfig = {
 *      SupervisorConfig(
 *        RestartStrategy(OneForOne, 3, 10),
 *        Worker(
 *          myFirstActorInstance,
 *          LifeCycle(Permanent, 1000))
 *        ::
 *        Worker(
 *          mySecondActorInstance,
 *          LifeCycle(Permanent, 1000))
 *        :: Nil)
 *    }
 * }
 * </pre>
 *
 * Then create a concrete factory in which we mix in support for the specific implementation of the Service we want to use.
 *
 * <pre>
 * object factory extends MySupervisorFactory
 * </pre>
 *
 * Then create a new Supervisor tree with the concrete Services we have defined.
 *
 * <pre>
 * val supervisor = factory.newSupervisor
 * supervisor ! Start // start up all managed servers
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class SupervisorFactory extends Logging {
  def newSupervisor: Supervisor = newSupervisorFor(getSupervisorConfig)

  def newSupervisorFor(config: SupervisorConfig): Supervisor = config match {
    case SupervisorConfig(restartStrategy, _) =>
      val supervisor = create(restartStrategy)
      supervisor.start
      supervisor !? Configure(config, this) match {
        case 'configSuccess => log.debug("Supervisor successfully configured")
        case _ => log.error("Supervisor could not be configured")
      }
      supervisor
  }

  /**
   * To be overridden by concrete factory.
   * Should return the SupervisorConfig for the supervisor.
   */
  protected def getSupervisorConfig: SupervisorConfig

  protected def create(strategy: RestartStrategy): Supervisor = strategy match {
    case RestartStrategy(scheme, maxNrOfRetries, timeRange) =>
      scheme match {
        case AllForOne => new Supervisor(new AllForOneStrategy(maxNrOfRetries, timeRange))
        case OneForOne => new Supervisor(new OneForOneStrategy(maxNrOfRetries, timeRange))
      }
  }
}

//====================================================
/**
 * TODO: document
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Supervisor(faultHandler: FaultHandlingStrategy) extends Actor with Logging {

  private val state = new SupervisorState(this, faultHandler)

  /**
   * Returns an Option with the GenericServerContainer for the server with the name specified.
   * If the server is found then Some(server) is returned else None.
   */
  def getServer(id: String): Option[GenericServerContainer] = state.getServerContainer(id)

  /**
   * Returns an the GenericServerContainer for the server with the name specified.
   * If the server is not found then the error handler is invoked.
   */
  def getServerOrElse(id: String, errorHandler: => GenericServerContainer): GenericServerContainer = {
    getServer(id) match {
      case Some(serverContainer) => serverContainer
      case None => errorHandler
    }
  }

  def stop = Actor.self ! Stop
  
  def act = {
    self.trapExit = true
    loop {
      react {
        case Configure(config, factory) =>
          log.debug("Configuring supervisor:%s ", this)
          configure(config, factory)
          reply('configSuccess)

        case Start =>
          state.serverContainers.foreach { serverContainer =>
            serverContainer.start
            log.info("Starting server: %s", serverContainer.getServer)
          }

        case Stop =>
          state.serverContainers.foreach { serverContainer =>
            serverContainer.terminate('normal)
            log.info("Stopping ser-ver: %s", serverContainer)
          }
          log.info("Stopping supervisor: %s", this)
          exit('normal)

        case Exit(failedServer, reason) =>
          reason match {
            case 'forced => {} // do nothing
            case _ => state.faultHandler.handleFailure(state, failedServer, reason)
          }

        case unexpected => log.warning("Unexpected message [%s] from [%s] ignoring...", unexpected, sender)
      }
    }
  }

  private def configure(config: SupervisorConfig, factory: SupervisorFactory) = config match {
    case SupervisorConfig(_, servers) =>
      servers.map(server =>
        server match {
          case Worker(serverContainer, lifecycle) =>
            serverContainer.lifeCycle = Some(lifecycle)
            spawnLink(serverContainer)

           case SupervisorConfig(_, _) => // recursive configuration
             val supervisor = factory.newSupervisorFor(server.asInstanceOf[SupervisorConfig])
             supervisor ! Start
             state.addSupervisor(supervisor)
        })
  }

  private[kernel] def spawnLink(serverContainer: GenericServerContainer): GenericServer = {
    val newServer = serverContainer.newServer()
    newServer.start
    self.link(newServer)
    log.debug("Linking actor [%s] to supervisor [%s]", newServer, this)
    state.addServerContainer(serverContainer)
    newServer
  }
}

/**
 * TODO: document
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class FaultHandlingStrategy(val maxNrOfRetries: Int, val withinTimeRange: Int) extends Logging {
  private[kernel] var supervisor: Supervisor = _
  private var nrOfRetries = 0
  private var retryStartTime = currentTime

  private[kernel] def handleFailure(state: SupervisorState, failedServer: AbstractActor, reason: AnyRef) = {
    nrOfRetries += 1
    if (timeRangeHasExpired) {
      if (hasReachedMaximumNrOfRetries) {
        log.info("Maximum of restarts [%s] for server [%s] has been reached - the supervisor including all its servers will now be shut down.", maxNrOfRetries, failedServer)
        supervisor ! Stop // execution stops here
      } else {
        nrOfRetries = 0
        retryStartTime = currentTime
      }
    }
    doHandleFailure(state, failedServer, reason)
  }

  private[kernel] def restart(serverContainer: GenericServerContainer, reason: AnyRef, state: SupervisorState) = {
    preRestart(serverContainer)
    serverContainer.lock.withWriteLock {
      // TODO: this is the place to fail-over all pending messages in the failing actor's mailbox, if possible to get a hold of them
      // e.g. something like 'serverContainer.getServer.getPendingMessages.map(newServer ! _)'

      self.unlink(serverContainer.getServer)
      serverContainer.lifeCycle match {
        case None =>
          throw new IllegalStateException("Server [" + serverContainer.id + "] does not have a life-cycle defined.")
        case Some(LifeCycle(scope, shutdownTime)) => {
          serverContainer.terminate(reason, shutdownTime)

          scope match {
            case Permanent => {
              log.debug("Restarting server [%s] configured as PERMANENT.", serverContainer.id)
              serverContainer.reconfigure(reason, supervisor.spawnLink(serverContainer), state.supervisor)
            }

            case Temporary =>
              if (reason == 'normal) {
                log.debug("Restarting server [%s] configured as TEMPORARY (since exited naturally).", serverContainer.id)
                serverContainer.reconfigure(reason, supervisor.spawnLink(serverContainer), state.supervisor)
              } else log.info("Server [%s] configured as TEMPORARY will not be restarted (received unnatural exit message).", serverContainer.id)

            case Transient =>
              log.info("Server [%s] configured as TRANSIENT will not be restarted.", serverContainer.id)
          }
        }
      }
    }
    postRestart(serverContainer)
  }

  /**
   * To be overriden by concrete strategies.
   */
  protected def doHandleFailure(state: SupervisorState, failedServer: AbstractActor, reason: AnyRef)

  /**
   * To be overriden by concrete strategies.
   */
  protected def preRestart(serverContainer: GenericServerContainer) = {}

  /**
   * To be overriden by concrete strategies.
   */
  protected def postRestart(serverContainer: GenericServerContainer) = {}

  private def hasReachedMaximumNrOfRetries: Boolean = nrOfRetries > maxNrOfRetries
  private def timeRangeHasExpired: Boolean = (currentTime - retryStartTime) > withinTimeRange
  private def currentTime: Long = System.currentTimeMillis
}

//====================================================
/**
 * TODO: document
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class AllForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int)
extends FaultHandlingStrategy(maxNrOfRetries, withinTimeRange) {
  override def doHandleFailure(state: SupervisorState, failedServer: AbstractActor, reason: AnyRef) = {
    log.error("Server [%s] has failed due to [%s] - scheduling restart - scheme: ALL_FOR_ONE.", failedServer, reason)
    for (serverContainer <- state.serverContainers) restart(serverContainer, reason, state)
    state.supervisors.foreach(_ ! Exit(failedServer, reason))
  }
}

//====================================================
/**
 * TODO: document
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class OneForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int)
extends FaultHandlingStrategy(maxNrOfRetries, withinTimeRange) {
  override def doHandleFailure(state: SupervisorState, failedServer: AbstractActor, reason: AnyRef) = {
    log.error("Server [%s] has failed due to [%s] - scheduling restart - scheme: ONE_FOR_ONE.", failedServer, reason)
    var serverContainer: Option[GenericServerContainer] = None
    state.serverContainers.foreach {
      container => if (container.getServer == failedServer) serverContainer = Some(container)
    }
    serverContainer match {
      case None => throw new RuntimeException("Could not find a generic server for actor: " + failedServer)
      case Some(container) => restart(container, reason, state)
    }
  }
}

//====================================================
/**
 * TODO: document
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
private[kernel] class SupervisorState(val supervisor: Supervisor, val faultHandler: FaultHandlingStrategy) extends Logging {
  faultHandler.supervisor = supervisor

  private val _lock = new ReadWriteLock
  private val _serverContainerRegistry = new HashMap[String, GenericServerContainer]
  private var _supervisors: List[Supervisor] = Nil

  def supervisors: List[Supervisor] = _lock.withReadLock {
    _supervisors
  }

  def addSupervisor(supervisor: Supervisor) = _lock.withWriteLock {
    _supervisors = supervisor :: _supervisors
  }

  def serverContainers: List[GenericServerContainer] = _lock.withReadLock {
    _serverContainerRegistry.values.toList
  }

  def getServerContainer(id: String): Option[GenericServerContainer] = _lock.withReadLock {
    if (_serverContainerRegistry.contains(id)) Some(_serverContainerRegistry(id))
    else None
  }

  def addServerContainer(serverContainer: GenericServerContainer) = _lock.withWriteLock {
    _serverContainerRegistry += serverContainer.id -> serverContainer
  }

  def removeServerContainer(id: String) = _lock.withWriteLock {
    getServerContainer(id) match {
      case Some(serverContainer) => _serverContainerRegistry - id
      case None => {}
    }
  }
}

