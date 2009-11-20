/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{ConfiguratorRepository, Configurator}
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.util.Logging

import java.util.concurrent.ConcurrentHashMap

/**
 * Messages that the supervisor responds to and returns.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
sealed abstract class SupervisorMessage
case object StartSupervisor extends SupervisorMessage
case object StopSupervisor extends SupervisorMessage
case class ConfigureSupervisor(config: SupervisorConfig, factory: SupervisorFactory) extends SupervisorMessage
case object ConfigSupervisorSuccess extends SupervisorMessage

sealed abstract class FaultHandlingStrategy
case class AllForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends FaultHandlingStrategy
case class OneForOneStrategy(maxNrOfRetries: Int, withinTimeRange: Int) extends FaultHandlingStrategy

/**
 * Abstract base class for all supervisor factories.
 * <p>
 * Example usage:
 * <pre>
 *  val factory = SupervisorFactory(
 *    SupervisorConfig(
 *      RestartStrategy(OneForOne, 3, 10),
 *      Supervise(
 *        myFirstActor,
 *        LifeCycle(Permanent)) ::
 *      Supervise(
 *        mySecondActor,
 *        LifeCycle(Permanent)) ::
 *      Nil))
 * </pre>
 *
 * Then create a new Supervisor tree with the concrete Services we have defined.
 *
 * <pre>
 * val supervisor = factory.newInstance
 * supervisor.start // start up all managed servers
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorFactory(val config: SupervisorConfig) extends Logging {

  def newInstance: Supervisor = newInstanceFor(config)

  def newInstanceFor(config: SupervisorConfig): Supervisor = config match {
    case SupervisorConfig(restartStrategy, _) =>
      val supervisor = create(restartStrategy)
      supervisor.start
      supervisor.configure(config, this)
      supervisor
  }

  protected def create(strategy: RestartStrategy): Supervisor = strategy match {
    case RestartStrategy(scheme, maxNrOfRetries, timeRange) =>
      scheme match {
        case AllForOne => new Supervisor(AllForOneStrategy(maxNrOfRetries, timeRange))
        case OneForOne => new Supervisor(OneForOneStrategy(maxNrOfRetries, timeRange))
      }
  }
}

object SupervisorFactory {
  def apply(config: SupervisorConfig) = new SupervisorFactory(config)
}

/**
 * <b>NOTE:</b>
 * <p/> 
 * The supervisor class is only used for the configuration system when configuring supervisor
 * hierarchies declaratively. Should not be used as part of the regular programming API. Instead
 * wire the actors together using 'link', 'spawnLink' etc. and set the 'trapExit' flag in the
 * actors that should trap error signals and trigger restart.
 * <p/> 
 * See the ScalaDoc for the SupervisorFactory for an example on how to declaratively wire up actors.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */                                  
sealed class Supervisor private[akka] (handler: FaultHandlingStrategy)
  extends Actor with Logging with Configurator {  
  trapExit = List(classOf[Throwable])
  faultHandler = Some(handler)
  //dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  val actors = new ConcurrentHashMap[String, Actor]
  
  def getInstance[T](clazz: Class[T]) = actors.get(clazz.getName).asInstanceOf[T]

  def getComponentInterfaces: List[Class[_]] = actors.values.toArray.toList.map(_.getClass)

  def isDefined(clazz: Class[_]): Boolean = actors.containsKey(clazz.getName)

  override def start = {
    ConfiguratorRepository.registerConfigurator(this)
    actors.values.toArray.toList.foreach(println)
    super[Actor].start
    this ! StartSupervisor
  }
  
  def stop = this !? StopSupervisor

  protected def receive = {
    case StartSupervisor =>
      _linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach { actor =>
        actor.start
        log.info("Starting actor: %s", actor)
      }

    case StopSupervisor =>
      _linkedActors.toArray.toList.asInstanceOf[List[Actor]].foreach { actor =>
        actor.exit
        log.info("Shutting actor down: %s", actor)
      }
      log.info("Stopping supervisor: %s", this)
      exit
  }

  def configure(config: SupervisorConfig, factory: SupervisorFactory) = config match {
    case SupervisorConfig(_, servers) =>
      servers.map(server =>
        server match {
          case Supervise(actor, lifecycle) =>
            actors.put(actor.getClass.getName, actor)
            actor.lifeCycle = Some(lifecycle)
            startLink(actor)

           case SupervisorConfig(_, _) => // recursive configuration
             val supervisor = factory.newInstanceFor(server.asInstanceOf[SupervisorConfig])
             supervisor ! StartSupervisor
             // FIXME what to do with recursively supervisors?
        })
  }
}
