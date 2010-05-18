/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.config.{AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy, ConfiguratorRepository, Configurator}
import se.scalablesolutions.akka.util.Helpers._
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.dispatch.Dispatchers
import se.scalablesolutions.akka.remote.RemoteServer
import Actor._
import java.util.concurrent.{CopyOnWriteArrayList, ConcurrentHashMap}

/**
 * Factory object for creating supervisors declarative. It creates instances of the 'Supervisor' class.
 * These are not actors, if you need a supervisor that is an Actor then you have to use the 'SupervisorActor'
 * factory object.
 * <p/>
 * 
 * Here is a sample on how to use it:
 * <pre>
 *  val supervisor = Supervisor(
 *    SupervisorConfig(
 *      RestartStrategy(OneForOne, 3, 10, List(classOf[Exception]),
 *      Supervise(
 *        myFirstActor,
 *        LifeCycle(Permanent)) ::
 *      Supervise(
 *        mySecondActor,
 *        LifeCycle(Permanent)) ::
 *      Nil))
 * </pre>
 *
 * You can use the declaratively created Supervisor to link and unlink child children
 * dynamically using the 'link' and 'unlink' methods.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Supervisor {
  def apply(config: SupervisorConfig): Supervisor = SupervisorFactory(config).newInstance.start
}

/**
 * Factory object for creating supervisors as Actors, it has both a declarative and programatic API.
 * <p/>
 * 
 * Here is a sample on how to use the programmatic API (note that the supervisor is automatically started):
 * <pre>
 * val supervisor = SupervisorActor(AllForOneStrategy(maxNrOfRetries, timeRange), Array(classOf[Throwable]))
 * 
 * // link and unlink child actors dynamically
 * supervisor ! Link(child1) // starts the actor if not started yet, starts and links atomically
 * supervisor ! Unlink(child2)
 * supervisor ! UnlinkAndStop(child3)
 * </pre>
 *
 * Here is a sample on how to use the declarative API:
 * <pre>
 *  val supervisor = SupervisorActor(
 *    SupervisorConfig(
 *      RestartStrategy(OneForOne, 3, 10, List(classOf[Exception]),
 *      Supervise(
 *        myFirstActor,
 *        LifeCycle(Permanent)) ::
 *      Supervise(
 *        mySecondActor,
 *        LifeCycle(Permanent)) ::
 *      Nil))
 * 
 * // link and unlink child actors dynamically
 * supervisor ! Link(child1) // starts the actor if not started yet, starts and links atomically
 * supervisor ! Unlink(child2)
 * supervisor ! UnlinkAndStop(child3)
 * </pre>
 *
 * You can use the declaratively created Supervisor to link and unlink child children
 * dynamically using the 'link' and 'unlink' methods.
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object SupervisorActor {
  def apply(config: SupervisorConfig): ActorRef = { 
    val (handler, trapExits) = SupervisorFactory.retrieveFaultHandlerAndTrapExitsFrom(config)
    actorOf(new SupervisorActor(handler, trapExits)).start
  }

  def apply(handler: FaultHandlingStrategy, trapExceptions: List[Class[_ <: Throwable]]): ActorRef = 
    actorOf(new SupervisorActor(handler, trapExceptions)).start
}

/**
 * Use this factory instead of the Supervisor factory object if you want to control
 * instantiation and starting of the Supervisor, if not then it is easier and better
 * to use the Supervisor factory object.
 * <p>
 * Example usage:
 * <pre>
 *  val factory = SupervisorFactory(
 *    SupervisorConfig(
 *      RestartStrategy(OneForOne, 3, 10, List(classOf[Exception]),
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
object SupervisorFactory {
  def apply(config: SupervisorConfig) = new SupervisorFactory(config)
  
  private[akka] def retrieveFaultHandlerAndTrapExitsFrom(config: SupervisorConfig): 
    Tuple2[FaultHandlingStrategy, List[Class[_ <: Throwable]]] = config match { 
    case SupervisorConfig(RestartStrategy(scheme, maxNrOfRetries, timeRange, trapExceptions), _) =>
      scheme match {
        case AllForOne => (AllForOneStrategy(maxNrOfRetries, timeRange), trapExceptions)
        case OneForOne => (OneForOneStrategy(maxNrOfRetries, timeRange), trapExceptions)
      }
    }
}

/**
 * For internal use only. 
 * 
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class SupervisorFactory private[akka] (val config: SupervisorConfig) extends Logging {
  type ExceptionList = List[Class[_ <: Throwable]]

  def newInstance: Supervisor = newInstanceFor(config)

  def newInstanceFor(config: SupervisorConfig): Supervisor = {
    val (handler, trapExits) = SupervisorFactory.retrieveFaultHandlerAndTrapExitsFrom(config)
    val supervisor = new Supervisor(handler, trapExits)
    supervisor.configure(config)
    supervisor.start
    supervisor
  }
}

/**
 * <b>NOTE:</b>
 * <p/> 
 * The supervisor class is only used for the configuration system when configuring supervisor
 * hierarchies declaratively. Should not be used as part of the regular programming API. Instead
 * wire the children together using 'link', 'spawnLink' etc. and set the 'trapExit' flag in the
 * children that should trap error signals and trigger restart.
 * <p/> 
 * See the ScalaDoc for the SupervisorFactory for an example on how to declaratively wire up children.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */                                  
sealed class Supervisor private[akka] (
  handler: FaultHandlingStrategy, trapExceptions: List[Class[_ <: Throwable]])
  extends Configurator {
  
  private val childActors = new ConcurrentHashMap[String, List[ActorRef]]
  private val childSupervisors = new CopyOnWriteArrayList[Supervisor] 
  private[akka] val supervisor = SupervisorActor(handler, trapExceptions)
   
  def uuid = supervisor.uuid
   
  def start: Supervisor = { 
    ConfiguratorRepository.registerConfigurator(this)
    this
  }
  
  def shutdown: Unit = supervisor.stop

  def link(child: ActorRef) = supervisor ! Link(child)

  def unlink(child: ActorRef) = supervisor ! Unlink(child)

  // FIXME recursive search + do not fix if we remove feature that Actors can be RESTful usin Jersey annotations
  def getInstance[T](clazz: Class[T]): List[T] = childActors.get(clazz.getName).asInstanceOf[List[T]]

  // FIXME recursive search + do not fix if we remove feature that Actors can be RESTful usin Jersey annotations
  def getComponentInterfaces: List[Class[_]] =
    childActors.values.toArray.toList.asInstanceOf[List[List[AnyRef]]].flatten.map(_.getClass)
  
  // FIXME recursive search + do not fix if we remove feature that Actors can be RESTful usin Jersey annotations
  def isDefined(clazz: Class[_]): Boolean = childActors.containsKey(clazz.getName)

  def configure(config: SupervisorConfig): Unit = config match {
    case SupervisorConfig(_, servers) =>
      servers.map(server =>
        server match {
          case Supervise(actorRef, lifeCycle, remoteAddress) =>
            val className = actorRef.actor.getClass.getName
            val currentActors = { 
              val list = childActors.get(className)
              if (list eq null) List[ActorRef]()
              else list
            }
            childActors.put(className, actorRef :: currentActors)
            actorRef.lifeCycle = Some(lifeCycle)
            supervisor ! Link(actorRef)
            remoteAddress.foreach { address => RemoteServer
              .actorsFor(RemoteServer.Address(address.hostname, address.port))
              .actors.put(actorRef.id, actorRef)
            }

          case supervisorConfig @ SupervisorConfig(_, _) => // recursive supervisor configuration
            val childSupervisor = Supervisor(supervisorConfig)
            supervisor ! Link(childSupervisor.supervisor)
            childSupervisors.add(childSupervisor)
        })
  }
}

/**
 * Use this class when you want to create a supervisor dynamically that should only
 * manage its child children and not have any functionality by itself.
 * <p/>
 * Here is a sample on how to use it:
 * <pre>
 * val supervisor = Supervisor(AllForOneStrategy(maxNrOfRetries, timeRange), Array(classOf[Throwable]))
 * supervisor ! Link(child1) // starts the actor if not started yet, starts and links atomically
 * supervisor ! Unlink(child2)
 * supervisor ! UnlinkAndStop(child3)
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class SupervisorActor private[akka] (
  handler: FaultHandlingStrategy, 
  trapExceptions: List[Class[_ <: Throwable]]) extends Actor {
  import self._
//  dispatcher = Dispatchers.newThreadBasedDispatcher(self) 
  trapExit = trapExceptions
  faultHandler = Some(handler)

  override def shutdown: Unit = shutdownLinkedActors

  def receive = {
    case Link(child)          => startLink(child)
    case Unlink(child)        => unlink(child)
    case UnlinkAndStop(child) => unlink(child); child.stop
    case unknown              => throw new IllegalArgumentException(
      "Supervisor can only respond to 'Link' and 'Unlink' messages. Unknown message [" + unknown + "]")
  }
}

