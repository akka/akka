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
import akka.event.{ Logging, DeathWatch, ActorClassification, EventStream }
import akka.routing.{ ScatterGatherFirstCompletedRouter, Routing, RouterType, Router, RoutedProps, RoutedActorRef, RoundRobinRouter, RandomRouter, LocalConnectionManager, DirectRouter }
import akka.AkkaException
import com.eaio.uuid.UUID
import akka.util.{ Switch, Helpers }

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String): ActorRef = actorOf(system, props, supervisor, name, false)

  def actorFor(path: Iterable[String]): Option[ActorRef]

  def guardian: ActorRef

  def systemGuardian: ActorRef

  def deathWatch: DeathWatch

  // FIXME: remove/replace
  def nodename: String

  def settings: ActorSystem.Settings

  def init(system: ActorSystemImpl)

  private[akka] def deployer: Deployer

  private[akka] def scheduler: Scheduler

  /**
   * Create an Actor with the given name below the given supervisor.
   */
  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef

  /**
   * Create an Actor with the given full path below the given supervisor.
   *
   * FIXME: Remove! this is dangerous!
   */
  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, path: ActorPath, systemService: Boolean): ActorRef

  /**
   * Remove this path from the lookup map.
   */
  private[akka] def evict(path: String): Boolean

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

  private val number = new AtomicLong

  private def randomName: String = {
    val l = number.getAndIncrement()
    Helpers.base64(l)
  }

  def actorOf(props: Props): ActorRef = provider.actorOf(systemImpl, props, guardian, randomName, false)

  /*
   * TODO this will have to go at some point, because creating two actors with
   * the same address can race on the cluster, and then you never know which
   * implementation wins
   */
  def actorOf(props: Props, name: String): ActorRef = {
    if (name == null || name == "" || name.startsWith("$"))
      throw new ActorInitializationException("actor name must not be null, empty or start with $")
    provider.actorOf(systemImpl, props, guardian, name, false)
  }

  def actorOf[T <: Actor](implicit m: Manifest[T]): ActorRef = actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def actorOf[T <: Actor](name: String)(implicit m: Manifest[T]): ActorRef =
    actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), name)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf(factory: ⇒ Actor): ActorRef = actorOf(Props(() ⇒ factory))

  def actorOf(creator: UntypedActorFactory): ActorRef = actorOf(Props(() ⇒ creator.create()))

  def actorFor(path: ActorPath): Option[ActorRef] = actorFor(path.path)

  def actorFor(path: String): Option[ActorRef] = actorFor(ActorPath.split(path))

  def actorFor(path: Iterable[String]): Option[ActorRef] = provider.actorFor(path)
}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(
  val settings: ActorSystem.Settings,
  val rootPath: ActorPath,
  val eventStream: EventStream,
  val dispatcher: MessageDispatcher,
  val scheduler: Scheduler) extends ActorRefProvider {

  val log = Logging(eventStream, this)

  // FIXME remove/replave (clustering shall not leak into akka-actor)
  val nodename: String = System.getProperty("akka.cluster.nodename") match {
    case null | "" ⇒ new UUID().toString
    case value     ⇒ value
  }

  private[akka] val deployer: Deployer = new Deployer(settings, eventStream, nodename)

  val terminationFuture = new DefaultPromise[Unit](Timeout.never)(dispatcher)

  /*
   * generate name for temporary actor refs
   */
  private val tempNumber = new AtomicLong
  def tempName = "$_" + Helpers.base64(tempNumber.getAndIncrement())
  private val tempNode = rootPath / "tmp"
  def tempPath = tempNode / tempName

  // FIXME (actor path): this could become a cache for the new tree traversal actorFor
  // currently still used for tmp actors (e.g. ask actor refs)
  private val actors = new ConcurrentHashMap[String, AnyRef]

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

    override def stop() = stopped switchOn { terminationFuture.complete(causeOfTermination.toLeft(())) }

    override def isShutdown = stopped.isOn

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
   * but it also requires these references to be @volatile.
   */
  @volatile
  private var rootGuardian: ActorRef = _
  @volatile
  private var _guardian: ActorRef = _
  @volatile
  private var _systemGuardian: ActorRef = _
  def guardian = _guardian
  def systemGuardian = _systemGuardian

  val deathWatch = createDeathWatch()

  def init(system: ActorSystemImpl) {
    rootGuardian = actorOf(system, guardianProps, theOneWhoWalksTheBubblesOfSpaceTime, rootPath, true)
    _guardian = actorOf(system, guardianProps, rootGuardian, "app", true)
    _systemGuardian = actorOf(system, guardianProps.withCreator(new SystemGuardian), rootGuardian, "sys", true)
    // chain death watchers so that killing guardian stops the application
    deathWatch.subscribe(_systemGuardian, _guardian)
    deathWatch.subscribe(rootGuardian, _systemGuardian)
  }

  // FIXME (actor path): should start at the new root guardian, and not use the tail (just to avoid the expected "system" name for now)
  def actorFor(path: Iterable[String]): Option[ActorRef] = findInCache(ActorPath.join(path)) orElse findInTree(Some(guardian), path.tail)

  @tailrec
  private def findInTree(start: Option[ActorRef], path: Iterable[String]): Option[ActorRef] = {
    if (path.isEmpty) start
    else {
      val child = start match {
        case Some(local: LocalActorRef) ⇒ local.underlying.getChild(path.head)
        case _                          ⇒ None
      }
      findInTree(child, path.tail)
    }
  }

  private def findInCache(path: String): Option[ActorRef] = actors.get(path) match {
    case null              ⇒ None
    case actor: ActorRef   ⇒ Some(actor)
    case future: Future[_] ⇒ Some(future.get.asInstanceOf[ActorRef])
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(path: String): Boolean = actors.remove(path) ne null

  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef =
    actorOf(system, props, supervisor, supervisor.path / name, systemService)

  private[akka] def actorOf(system: ActorSystemImpl, props: Props, supervisor: ActorRef, path: ActorPath, systemService: Boolean): ActorRef = {
    val name = path.name
    val newFuture = Promise[ActorRef](5000)(dispatcher) // FIXME is this proper timeout?

    actors.putIfAbsent(path.toString, newFuture) match {
      case null ⇒
        val actor: ActorRef = try {
          (if (systemService) None else deployer.lookupDeployment(path.toString)) match { // see if the deployment already exists, if so use it, if not create actor

            // create a local actor
            case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, DeploymentConfig.LocalScope)) ⇒
              new LocalActorRef(system, props, supervisor, path, systemService) // create a local actor

            // create a routed actor ref
            case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, DeploymentConfig.LocalScope)) ⇒

              val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                case RouterType.Direct     ⇒ () ⇒ new DirectRouter
                case RouterType.Random     ⇒ () ⇒ new RandomRouter
                case RouterType.RoundRobin ⇒ () ⇒ new RoundRobinRouter
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
        } catch {
          case e: Exception ⇒
            newFuture completeWithException e // so the other threads gets notified of error
            //TODO FIXME should we remove the mapping in "actors" here?
            throw e
        }

        newFuture completeWithResult actor
        actors.replace(path.toString, newFuture, actor)
        actor
      case actor: ActorRef ⇒
        actor
      case future: Future[_] ⇒
        future.get.asInstanceOf[ActorRef]
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

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = actorFor(ActorPath.split(actor.path))
  private[akka] def serialize(actor: ActorRef): SerializedActorRef = new SerializedActorRef(rootPath.remoteAddress, actor.path.toString)

  private[akka] def createDeathWatch(): DeathWatch = new LocalDeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = {
    import akka.dispatch.DefaultPromise
    (if (within == null) settings.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒
        new DefaultPromise[Any](0)(dispatcher) //Abort early if nonsensical timeout
      case t ⇒
        val a = new AskActorRef(tempPath, this, deathWatch, t, dispatcher) { def whenDone() = actors.remove(this) }
        assert(actors.putIfAbsent(a.path.toString, a) eq null) //If this fails, we're in deep trouble
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

class DefaultScheduler(hashedWheelTimer: HashedWheelTimer) extends Scheduler {

  def schedule(receiver: ActorRef, message: Any, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(receiver, message, delay, timeUnit), initialDelay, timeUnit))

  def scheduleOnce(runnable: Runnable, delay: Long, timeUnit: TimeUnit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(runnable), delay, timeUnit))

  def scheduleOnce(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(receiver, message), delay, timeUnit))

  def schedule(f: () ⇒ Unit, initialDelay: Long, delay: Long, timeUnit: TimeUnit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(f, delay, timeUnit), initialDelay, timeUnit))

  def scheduleOnce(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(f), delay, timeUnit))

  private def createSingleTask(runnable: Runnable): TimerTask =
    new TimerTask() { def run(timeout: org.jboss.netty.akka.util.Timeout) { runnable.run() } }

  private def createSingleTask(receiver: ActorRef, message: Any): TimerTask =
    new TimerTask { def run(timeout: org.jboss.netty.akka.util.Timeout) { receiver ! message } }

  private def createContinuousTask(receiver: ActorRef, message: Any, delay: Long, timeUnit: TimeUnit): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        receiver ! message
        timeout.getTimer.newTimeout(this, delay, timeUnit)
      }
    }
  }

  private def createSingleTask(f: () ⇒ Unit): TimerTask =
    new TimerTask { def run(timeout: org.jboss.netty.akka.util.Timeout) { f() } }

  private def createContinuousTask(f: () ⇒ Unit, delay: Long, timeUnit: TimeUnit): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        f()
        timeout.getTimer.newTimeout(this, delay, timeUnit)
      }
    }
  }

  private[akka] def stop() = hashedWheelTimer.stop()
}

class DefaultCancellable(timeout: org.jboss.netty.akka.util.Timeout) extends Cancellable {
  def cancel() { timeout.cancel() }

  def isCancelled: Boolean = { timeout.isCancelled }
}

