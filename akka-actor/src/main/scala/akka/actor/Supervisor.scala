/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import akka.AkkaException
import akka.util._
import ReflectiveAccess._
import Actor._

import java.util.concurrent.{ CopyOnWriteArrayList }
import akka.config.Supervision._
import collection.mutable.ListBuffer

class SupervisorException private[akka] (message: String, cause: Throwable = null) extends AkkaException(message, cause){
  def this(message: String) = this(message, null)
}

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
 *        Permanent) ::
 *      Supervise(
 *        mySecondActor,
 *        Permanent) ::
 *      Nil))
 * </pre>
 *
 * You dynamically link and unlink child children using the 'link' and 'unlink' methods.
 * <pre>
 * supervisor.link(child)
 * supervisor.unlink(child)
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Supervisor {
  def apply(config: SupervisorConfig): Supervisor = SupervisorFactory(config).newInstance.start
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
 *        Permanent) ::
 *      Supervise(
 *        mySecondActor,
 *        Permanent) ::
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
case class SupervisorFactory(val config: SupervisorConfig) {

  def newInstance: Supervisor = newInstanceFor(config)

  def newInstanceFor(config: SupervisorConfig): Supervisor = {
    val supervisor = new Supervisor(config.restartStrategy, config.maxRestartsHandler)
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
sealed class Supervisor(handler: FaultHandlingStrategy, maxRestartsHandler: (ActorRef, MaximumNumberOfRestartsWithinTimeRangeReached) ⇒ Unit) {
  import Supervisor._

  private val _childActors = new Index[String, ActorRef]
  private val _childSupervisors = new CopyOnWriteArrayList[Supervisor]

  private[akka] val supervisor = actorOf(new SupervisorActor(handler, maxRestartsHandler)).start()

  def uuid = supervisor.uuid

  def start: Supervisor = {
    this
  }

  def shutdown(): Unit = supervisor.stop()

  def link(child: ActorRef) = supervisor.link(child)

  def unlink(child: ActorRef) = supervisor.unlink(child)

  def children: List[ActorRef] = {
    val buf = new ListBuffer[ActorRef]
    _childActors foreach { case (k, v) => buf += v }
    buf.toList
  }

  def childSupervisors: List[Supervisor] = {
    val buf = new ListBuffer[Supervisor]
    val i = _childSupervisors.iterator()
    while(i.hasNext) buf += i.next()
    buf.toList
  }

  def configure(config: SupervisorConfig): Unit = config match {
    case SupervisorConfig(_, servers, _) ⇒
      servers foreach {
        case Supervise(actorRef, lifeCycle, registerAsRemoteService) ⇒
          actorRef.lifeCycle = lifeCycle
          supervisor.startLink(actorRef)

          _childActors.put(actorRef.actor.getClass.getName, actorRef) //TODO Why do we keep this here, mem leak?

          if (registerAsRemoteService)
            Actor.remote.register(actorRef)
        case supervisorConfig@SupervisorConfig(_, _, _) ⇒ // recursive supervisor configuration
          val childSupervisor = Supervisor(supervisorConfig)
          supervisor.link(childSupervisor.supervisor)
          _childSupervisors.add(childSupervisor)
      }
  }
}

/**
 * For internal use only.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final class SupervisorActor private[akka] (handler: FaultHandlingStrategy, maxRestartsHandler: (ActorRef, MaximumNumberOfRestartsWithinTimeRangeReached) ⇒ Unit) extends Actor {
  self.faultHandler = handler

  override def postStop(): Unit = {
    val i = self.linkedActors.values.iterator
    while (i.hasNext) {
      val ref = i.next
      ref.stop()
      self.unlink(ref)
    }
  }

  def receive = {
    case max@MaximumNumberOfRestartsWithinTimeRangeReached(_, _, _, _) ⇒ maxRestartsHandler(self, max)
    case unknown ⇒ throw new SupervisorException(
      "SupervisorActor can not respond to messages.\n\tUnknown message [" + unknown + "]")
  }
}
