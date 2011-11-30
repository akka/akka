/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }
import scala.annotation.tailrec
import org.jboss.netty.akka.util.{ TimerTask, HashedWheelTimer }
import akka.actor.Timeout.intToTimeout
import akka.config.ConfigurationException
import akka.dispatch.{ SystemMessage, Supervise, Promise, MessageDispatcher, Future, DefaultPromise, Dispatcher, Mailbox, Envelope }
import akka.routing.{ ScatterGatherFirstCompletedRouter, Routing, RouterType, Router, RoutedProps, RoutedActorRef, RoundRobinRouter, RandomRouter, LocalConnectionManager, DirectRouter, BroadcastRouter }
import akka.AkkaException
import com.eaio.uuid.UUID
import akka.util.{ Duration, Switch, Helpers }
import akka.remote.RemoteAddress
import org.jboss.netty.akka.util.internal.ConcurrentIdentityHashMap
import akka.event._
import akka.event.Logging.Error._
import akka.event.Logging.Warning

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  /**
   * Reference to the supervisor used for all top-level user actors.
   */
  def guardian: ActorRef

  /**
   * Reference to the supervisor used for all top-level system actors.
   */
  def systemGuardian: ActorRef

  /**
   * Reference to the death watch service.
   */
  def deathWatch: DeathWatch

  // FIXME: remove/replace?
  def nodename: String

  // FIXME: remove/replace?
  def clustername: String

  /**
   * The root path for all actors within this actor system, including remote
   * address if enabled.
   */
  def rootPath: ActorPath

  def settings: ActorSystem.Settings

  /**
   * Initialization of an ActorRefProvider happens in two steps: first
   * construction of the object with settings, eventStream, scheduler, etc.
   * and then—when the ActorSystem is constructed—the second phase during
   * which actors may be created (e.g. the guardians).
   */
  def init(system: ActorSystemImpl)

  private[akka] def deployer: Deployer

  private[akka] def scheduler: Scheduler

  /**
   * Actor factory with create-only semantics: will create an actor as
   * described by props with the given supervisor and path (may be different
   * in case of remote supervision). If systemService is true, deployment is
   * bypassed (local-only).
   */
  def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String, systemService: Boolean = false): ActorRef

  /**
   * Create actor reference for a specified local or remote path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   */
  def actorFor(path: ActorPath): ActorRef

  /**
   * Create actor reference for a specified local or remote path, which will
   * be parsed using java.net.URI. If no such actor exists, it will be
   * (equivalent to) a dead letter reference.
   */
  def actorFor(s: String): ActorRef

  /**
   * Create actor reference for the specified child path starting at the root
   * guardian. This method always returns an actor which is “logically local”,
   * i.e. it cannot be used to obtain a reference to an actor which is not
   * physically or logically attached to this actor system.
   */
  def actorFor(p: Iterable[String]): ActorRef

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef]

  private[akka] def serialize(actor: ActorRef): SerializedActorRef

  private[akka] def createDeathWatch(): DeathWatch

  /**
   * Create AskActorRef to hook up message send to recipient with Future receiver.
   */
  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any]

  /**
   * This Future is completed upon termination of this ActorRefProvider, which
   * is usually initiated by stopping the guardian via ActorSystem.stop().
   */
  private[akka] def terminationFuture: Future[Unit]
}

/**
 * Interface implemented by ActorSystem and AkkaContext, the only two places from which you can get fresh actors
 */
trait ActorRefFactory {

  protected def systemImpl: ActorSystemImpl

  protected def provider: ActorRefProvider

  protected def dispatcher: MessageDispatcher

  /**
   * Father of all children created by this interface.
   */
  protected def guardian: ActorRef

  protected def randomName(): String

  /**
   * Child names must be unique within the context of one parent; implement
   * this method to have the default implementation of actorOf perform the
   * check (and throw an exception if necessary).
   */
  protected def isDuplicate(name: String): Boolean

  /**
   * This method is called after successfully associating an ActorRef with a
   * name; that name will have been found to be “no duplicate” by isDuplicate(),
   * so this hook is used to implement a reservation scheme: isDuplicate
   * reserves a name slot and actorCreated() fills it with the right ActorRef.
   */
  protected def actorCreated(name: String, actor: ActorRef): Unit

  def actorOf(props: Props): ActorRef = provider.actorOf(systemImpl, props, guardian, randomName(), false)

  def actorOf(props: Props, name: String): ActorRef = {
    if (name == null || name == "" || name.startsWith("$"))
      throw new InvalidActorNameException("actor name must not be null, empty or start with $")
    if (isDuplicate(name))
      throw new InvalidActorNameException("actor name " + name + " is not unique!")
    val a = provider.actorOf(systemImpl, props, guardian, name, false)
    actorCreated(name, a)
    a
  }

  def actorOf[T <: Actor](implicit m: Manifest[T]): ActorRef = actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def actorOf[T <: Actor](name: String)(implicit m: Manifest[T]): ActorRef =
    actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), name)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf(factory: ⇒ Actor): ActorRef = actorOf(Props(() ⇒ factory))

  def actorOf(creator: UntypedActorFactory): ActorRef = actorOf(Props(() ⇒ creator.create()))

  def actorOf(creator: UntypedActorFactory, name: String): ActorRef = actorOf(Props(() ⇒ creator.create()), name)

  def actorFor(path: ActorPath) = provider.actorFor(path)

  def actorFor(path: String): ActorRef = provider.actorFor(path)

  def actorFor(path: Iterable[String]): ActorRef = provider.actorFor(path)
}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(
  _systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  val deadLetters: ActorRef) extends ActorRefProvider {

  val rootPath: ActorPath = new RootActorPath(LocalAddress(_systemName))

  // FIXME remove both
  val nodename: String = "local"
  val clustername: String = "local"

  val log = Logging(eventStream, "LocalActorRefProvider")

  private[akka] val deployer: Deployer = new Deployer(settings, eventStream, nodename)

  /*
   * generate name for temporary actor refs
   */
  private val tempNumber = new AtomicLong

  private def tempName = "$_" + Helpers.base64(tempNumber.getAndIncrement())

  private val tempNode = rootPath / "tmp"

  def tempPath = tempNode / tempName

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: ActorRef = new MinimalActorRef {
    val stopped = new Switch(false)

    @volatile
    var causeOfTermination: Option[Throwable] = None

    override val name = "bubble-walker"

    // FIXME (actor path): move the root path to the new root guardian
    val path = rootPath / name

    val address = path.toString

    override def toString = name

    override def stop() = stopped switchOn {
      terminationFuture.complete(causeOfTermination.toLeft(()))
    }

    override def isTerminated = stopped.isOn

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = stopped.ifOff(message match {
      case Failed(ex)      ⇒ causeOfTermination = Some(ex); sender.stop()
      case ChildTerminated ⇒ stop()
      case _               ⇒ log.error(this + " received unexpected message " + message)
    })

    protected[akka] override def sendSystemMessage(message: SystemMessage): Unit = stopped ifOff {
      message match {
        case Supervise(child) ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case _                ⇒ log.error(this + " received unexpected system message " + message)
      }
    }
  }

  private class Guardian extends Actor {
    def receive = {
      case Terminated(_) ⇒ context.self.stop()
    }
  }

  private class SystemGuardian extends Actor {
    def receive = {
      case Terminated(_) ⇒
        eventStream.stopDefaultLoggers()
        context.self.stop()
    }
  }

  private val guardianFaultHandlingStrategy = {
    import akka.actor.FaultHandlingStrategy._
    OneForOneStrategy {
      case _: ActorKilledException         ⇒ Stop
      case _: ActorInitializationException ⇒ Stop
      case _: Exception                    ⇒ Restart
    }
  }
  private val guardianProps = Props(new Guardian).withFaultHandler(guardianFaultHandlingStrategy)

  /*
   * The problem is that ActorRefs need a reference to the ActorSystem to
   * provide their service. Hence they cannot be created while the
   * constructors of ActorSystem and ActorRefProvider are still running.
   * The solution is to split out that last part into an init() method,
   * but it also requires these references to be @volatile and lazy.
   */
  @volatile
  private var system: ActorSystemImpl = _

  def dispatcher: MessageDispatcher = system.dispatcher

  lazy val terminationFuture: DefaultPromise[Unit] = new DefaultPromise[Unit](Timeout.never)(dispatcher)
  lazy val rootGuardian: ActorRef = new LocalActorRef(system, guardianProps, theOneWhoWalksTheBubblesOfSpaceTime, rootPath, true)
  lazy val guardian: ActorRef = actorOf(system, guardianProps, rootGuardian, "app", true)
  lazy val systemGuardian: ActorRef = actorOf(system, guardianProps.withCreator(new SystemGuardian), rootGuardian, "sys", true)

  val deathWatch = createDeathWatch()

  def init(_system: ActorSystemImpl) {
    system = _system
    // chain death watchers so that killing guardian stops the application
    deathWatch.subscribe(systemGuardian, guardian)
    deathWatch.subscribe(rootGuardian, systemGuardian)
  }

  def actorFor(path: String): ActorRef = path match {
    case LocalActorPath(address, elems) if address == rootPath.address ⇒
      findInTree(rootGuardian.asInstanceOf[LocalActorRef], elems)
    case _ ⇒ deadLetters
  }

  def actorFor(path: ActorPath): ActorRef = findInTree(rootGuardian.asInstanceOf[LocalActorRef], path.pathElements)

  def actorFor(path: Iterable[String]): ActorRef = findInTree(rootGuardian.asInstanceOf[LocalActorRef], path)

  @tailrec
  private def findInTree(start: LocalActorRef, path: Iterable[String]): ActorRef = {
    if (path.isEmpty) start
    else start.underlying.getChild(path.head) match {
      case null                 ⇒ deadLetters
      case child: LocalActorRef ⇒ findInTree(child, path.tail)
      case _                    ⇒ deadLetters
    }
  }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef = {
    val path = supervisor.path / name
    (if (systemService) None else deployer.lookupDeployment(path.toString)) match {

      // create a local actor
      case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, DeploymentConfig.LocalScope)) ⇒
        new LocalActorRef(system, props, supervisor, path, systemService) // create a local actor

      // create a routed actor ref
      case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, DeploymentConfig.LocalScope)) ⇒
        implicit val dispatcher = if (props.dispatcher == Props.defaultDispatcher) system.dispatcher else props.dispatcher
        implicit val timeout = system.settings.ActorTimeout
        val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
          case RouterType.Direct     ⇒ () ⇒ new DirectRouter
          case RouterType.Random     ⇒ () ⇒ new RandomRouter
          case RouterType.RoundRobin ⇒ () ⇒ new RoundRobinRouter
          case RouterType.Broadcast  ⇒ () ⇒ new BroadcastRouter
          case RouterType.ScatterGather ⇒ () ⇒ new ScatterGatherFirstCompletedRouter()(
            if (props.dispatcher == Props.defaultDispatcher) dispatcher else props.dispatcher, settings.ActorTimeout)
          case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
          case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
          case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
          case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
        }

        val connections: Iterable[ActorRef] = (1 to nrOfInstances.factor) map { i ⇒
          val routedPath = path.parent / (path.name + ":" + i)
          new LocalActorRef(system, props, supervisor, routedPath, systemService)
        }

        actorOf(system, RoutedProps(routerFactory = routerFactory, connectionManager = new LocalConnectionManager(connections)), supervisor, path.toString)

      case unknown ⇒ throw new Exception("Don't know how to create this actor ref! Why? Got: " + unknown)
    }
  }

  /**
   * Creates (or fetches) a routed actor reference, configured by the 'props: RoutedProps' configuration.
   */
  def actorOf(system: ActorSystem, props: RoutedProps, supervisor: ActorRef, name: String): ActorRef = {
    // FIXME: this needs to take supervision into account!

    //FIXME clustering should be implemented by cluster actor ref provider
    //TODO Implement support for configuring by deployment ID etc
    //TODO If address matches an already created actor (Ahead-of-time deployed) return that actor
    //TODO If address exists in config, it will override the specified Props (should we attempt to merge?)
    //TODO If the actor deployed uses a different config, then ignore or throw exception?
    if (props.connectionManager.isEmpty) throw new ConfigurationException("RoutedProps used for creating actor [" + name + "] has zero connections configured; can't create a router")
    // val clusteringEnabled = ReflectiveAccess.ClusterModule.isEnabled
    // val localOnly = props.localOnly
    // if (clusteringEnabled && !props.localOnly) ReflectiveAccess.ClusterModule.newClusteredActorRef(props)
    // else new RoutedActorRef(props, address)
    new RoutedActorRef(system, props, supervisor, name)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = Some(actorFor(actor.path))

  private[akka] def serialize(actor: ActorRef): SerializedActorRef = new SerializedActorRef(rootPath.address, actor.path.toString)

  private[akka] def createDeathWatch(): DeathWatch = new LocalDeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = {
    import akka.dispatch.DefaultPromise
    (if (within == null) settings.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒
        new DefaultPromise[Any](0)(dispatcher) //Abort early if nonsensical timeout
      case t ⇒
        val a = new AskActorRef(tempPath, this, deathWatch, t, dispatcher)
        recipient.tell(message, a)
        a.result
    }
  }
}

class LocalDeathWatch extends DeathWatch with ActorClassification {

  def mapSize = 1024

  override def publish(event: Event): Unit = {
    val monitors = dissociate(classify(event))
    if (monitors.nonEmpty) monitors.foreach(_ ! event)
  }

  override def subscribe(subscriber: Subscriber, to: Classifier): Boolean = {
    if (!super.subscribe(subscriber, to)) {
      subscriber ! Terminated(to)
      false
    } else true
  }
}

class DefaultScheduler(hashedWheelTimer: HashedWheelTimer, system: ActorSystem) extends Scheduler {

  def schedule(receiver: ActorRef, message: Any, initialDelay: Duration, delay: Duration): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(receiver, message, delay), initialDelay))

  def schedule(f: () ⇒ Unit, initialDelay: Duration, delay: Duration): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(f, delay), initialDelay))

  def scheduleOnce(runnable: Runnable, delay: Duration): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(runnable), delay))

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Duration): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(receiver, message), delay))

  def scheduleOnce(f: () ⇒ Unit, delay: Duration): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(f), delay))

  private def createSingleTask(runnable: Runnable): TimerTask =
    new TimerTask() {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        // FIXME: consider executing runnable inside main dispatcher to prevent blocking of scheduler
        runnable.run()
      }
    }

  private def createSingleTask(receiver: ActorRef, message: Any): TimerTask =
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        receiver ! message
      }
    }

  private def createSingleTask(f: () ⇒ Unit): TimerTask =
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        f()
      }
    }

  private def createContinuousTask(receiver: ActorRef, message: Any, delay: Duration): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        // Check if the receiver is still alive and kicking before sending it a message and reschedule the task
        if (!receiver.isTerminated) {
          receiver ! message
          timeout.getTimer.newTimeout(this, delay)
        } else {
          system.eventStream.publish(Warning(this.getClass.getSimpleName, "Could not reschedule message to be sent because receiving actor has been terminated."))
        }
      }
    }
  }

  private def createContinuousTask(f: () ⇒ Unit, delay: Duration): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        f()
        timeout.getTimer.newTimeout(this, delay)
      }
    }
  }

  private[akka] def stop() = hashedWheelTimer.stop()
}

class DefaultCancellable(val timeout: org.jboss.netty.akka.util.Timeout) extends Cancellable {
  def cancel() {
    timeout.cancel()
  }

  def isCancelled: Boolean = {
    timeout.isCancelled
  }
}

