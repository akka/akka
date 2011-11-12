/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.config.ConfigurationException
import akka.util.ReflectiveAccess
import akka.routing._
import com.eaio.uuid.UUID
import akka.AkkaException
import akka.dispatch._
import scala.annotation.tailrec
import org.jboss.netty.akka.util.HashedWheelTimer
import java.util.concurrent.{ TimeUnit, Executors, ConcurrentHashMap }
import akka.event.{ LoggingAdapter, ActorClassification, DeathWatch, Logging }

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, supervisor: ActorRef, name: String): ActorRef = actorOf(props, supervisor, name, false)

  def actorFor(path: Iterable[String]): Option[ActorRef]

  /**
   * What deployer will be used to resolve deployment configuration?
   */
  private[akka] def deployer: Deployer

  private[akka] def scheduler: Scheduler

  private[akka] def actorOf(props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef

  private[akka] def actorOf(props: Props, supervisor: ActorRef, path: ActorPath, systemService: Boolean): ActorRef

  private[akka] def evict(path: String): Boolean

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef]

  private[akka] def serialize(actor: ActorRef): SerializedActorRef

  private[akka] def createDeathWatch(): DeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any]

  private[akka] def theOneWhoWalksTheBubblesOfSpaceTime: ActorRef

  private[akka] def terminationFuture: Future[ActorSystem.ExitStatus]

  private[akka] def dummyAskSender: ActorRef
}

/**
 * Interface implemented by ActorSystem and AkkaContext, the only two places from which you can get fresh actors
 */
trait ActorRefFactory {

  def provider: ActorRefProvider

  def dispatcher: MessageDispatcher

  /**
   * Father of all children created by this interface.
   */
  protected def guardian: ActorRef

  def actorOf(props: Props): ActorRef = actorOf(props, Props.randomName)

  /*
   * TODO this will have to go at some point, because creating two actors with
   * the same address can race on the cluster, and then you never know which
   * implementation wins
   */
  def actorOf(props: Props, name: String): ActorRef = provider.actorOf(props, guardian, name, false)

  def actorOf[T <: Actor](implicit m: Manifest[T]): ActorRef = actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def actorOf[T <: Actor](name: String)(implicit m: Manifest[T]): ActorRef =
    actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), name)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf(factory: ⇒ Actor): ActorRef = actorOf(Props(() ⇒ factory))

  def actorOf(creator: UntypedActorFactory): ActorRef = actorOf(Props(() ⇒ creator.create()))

  def actorFor(path: String): Option[ActorRef] = actorFor(ActorPath.split(path))

  def actorFor(path: Iterable[String]): Option[ActorRef] = provider.actorFor(path)
}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(val app: ActorSystem) extends ActorRefProvider {

  val log = Logging(app.eventStream, this)

  private[akka] val deployer: Deployer = new Deployer(app)

  val terminationFuture = new DefaultPromise[ActorSystem.ExitStatus](Timeout.never)(app.dispatcher)

  private[akka] val scheduler: Scheduler = { //TODO FIXME Make this configurable
    val s = new DefaultScheduler(new HashedWheelTimer(log, Executors.defaultThreadFactory, 100, TimeUnit.MILLISECONDS, 512))
    terminationFuture.onComplete(_ ⇒ s.stop())
    s
  }

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: ActorRef = new UnsupportedActorRef {
    @volatile
    var stopped = false

    val name = app.name + "-bubble-walker"

    // FIXME (actor path): move the root path to the new root guardian
    val path = app.root

    val address = app.address + path.toString

    override def toString = name

    def stop() = stopped = true

    def isShutdown = stopped

    override def tell(msg: Any, sender: ActorRef): Unit = msg match {
      case Failed(child, ex)      ⇒ child.stop()
      case ChildTerminated(child) ⇒ terminationFuture.completeWithResult(ActorSystem.Stopped)
      case _                      ⇒ log.error(this + " received unexpected message " + msg)
    }

    protected[akka] override def sendSystemMessage(message: SystemMessage) {
      message match {
        case Supervise(child) ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case _                ⇒ log.error(this + " received unexpected system message " + message)
      }
    }
  }

  // FIXME (actor path): this could become a cache for the new tree traversal actorFor
  // currently still used for tmp actors (e.g. ask actor refs)
  private val actors = new ConcurrentHashMap[String, AnyRef]

  // FIXME (actor path): should start at the new root guardian, and not use the tail (just to avoid the expected "app" name for now)
  def actorFor(path: Iterable[String]): Option[ActorRef] = findInCache(ActorPath.join(path)) orElse findInTree(Some(app.guardian), path.tail)

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

  private[akka] def actorOf(props: Props, supervisor: ActorRef, name: String, systemService: Boolean): ActorRef =
    actorOf(props, supervisor, supervisor.path / name, systemService)

  private[akka] def actorOf(props: Props, supervisor: ActorRef, path: ActorPath, systemService: Boolean): ActorRef = {
    val name = path.name
    if ((name eq null) || name == Props.randomName) {
      val randomName: String = newUuid.toString
      val newPath = path.parent / randomName
      val actor = new LocalActorRef(app, props, supervisor, newPath, systemService = true)
      actors.putIfAbsent(newPath.toString, actor) match {
        case null  ⇒ actor
        case other ⇒ throw new IllegalStateException("Same path generated twice for: " + actor + " and " + other)
      }
    } else {
      val newFuture = Promise[ActorRef](5000)(app.dispatcher) // FIXME is this proper timeout?

      actors.putIfAbsent(path.toString, newFuture) match {
        case null ⇒
          val actor: ActorRef = try {
            (if (systemService) None else deployer.lookupDeployment(path.toString)) match { // see if the deployment already exists, if so use it, if not create actor

              // create a local actor
              case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, DeploymentConfig.LocalScope)) ⇒
                new LocalActorRef(app, props, supervisor, path, systemService) // create a local actor

              // create a routed actor ref
              case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, DeploymentConfig.LocalScope)) ⇒

                val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
                  case RouterType.Direct     ⇒ () ⇒ new DirectRouter
                  case RouterType.Random     ⇒ () ⇒ new RandomRouter
                  case RouterType.RoundRobin ⇒ () ⇒ new RoundRobinRouter
                  case RouterType.ScatterGather ⇒ () ⇒ new ScatterGatherFirstCompletedRouter()(
                    if (props.dispatcher == Props.defaultDispatcher) app.dispatcher else props.dispatcher, app.AkkaConfig.ActorTimeout)
                  case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
                  case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
                  case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
                  case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
                }

                val connections: Iterable[ActorRef] = (1 to nrOfInstances.factor) map { i ⇒
                  val routedPath = path.parent / (path.name + ":" + i)
                  new LocalActorRef(app, props, supervisor, routedPath, systemService)
                }

                actorOf(RoutedProps(routerFactory = routerFactory, connectionManager = new LocalConnectionManager(connections)), supervisor, path.toString)

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

  }

  /**
   * Creates (or fetches) a routed actor reference, configured by the 'props: RoutedProps' configuration.
   */
  def actorOf(props: RoutedProps, supervisor: ActorRef, name: String): ActorRef = {
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
    new RoutedActorRef(app, props, supervisor, name)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = actorFor(ActorPath.split(actor.path))
  private[akka] def serialize(actor: ActorRef): SerializedActorRef = new SerializedActorRef(app.address, actor.path.toString)

  private[akka] def createDeathWatch(): DeathWatch = new LocalDeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = {
    import akka.dispatch.{ Future, Promise, DefaultPromise }
    (if (within == null) app.AkkaConfig.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒ new DefaultPromise[Any](0)(app.dispatcher) //Abort early if nonsensical timeout
      case t ⇒
        val a = new AskActorRef(app)(timeout = t) { def whenDone() = actors.remove(this) }
        assert(actors.putIfAbsent(a.path.toString, a) eq null) //If this fails, we're in deep trouble
        recipient.tell(message, a)
        a.result
    }
  }

  private[akka] val dummyAskSender = new DeadLetterActorRef(app)
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

import org.jboss.netty.akka.util.{ HashedWheelTimer, TimerTask }
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

