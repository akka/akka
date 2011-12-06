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
import akka.dispatch._
import akka.routing._
import akka.AkkaException
import com.eaio.uuid.UUID
import akka.util.{ Duration, Switch, Helpers }
import akka.remote.RemoteAddress
import org.jboss.netty.akka.util.internal.ConcurrentIdentityHashMap
import akka.event._
import akka.event.Logging.Error._
import akka.event.Logging.Warning
import java.io.Closeable

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  /**
   * Reference to the supervisor of guardian and systemGuardian; this is
   * exposed so that the ActorSystemImpl can use it as lookupRoot, i.e.
   * for anchoring absolute actor look-ups.
   */
  def rootGuardian: InternalActorRef

  /**
   * Reference to the supervisor used for all top-level user actors.
   */
  def guardian: InternalActorRef

  /**
   * Reference to the supervisor used for all top-level system actors.
   */
  def systemGuardian: InternalActorRef

  /**
   * Reference to the death watch service.
   */
  def deathWatch: DeathWatch

  // FIXME: remove/replace???
  def nodename: String

  // FIXME: remove/replace???
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
  def init(system: ActorSystemImpl): Unit

  private[akka] def deployer: Deployer

  private[akka] def scheduler: Scheduler

  /**
   * Actor factory with create-only semantics: will create an actor as
   * described by props with the given supervisor and path (may be different
   * in case of remote supervision). If systemService is true, deployment is
   * bypassed (local-only).
   */
  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, name: String, systemService: Boolean = false): InternalActorRef

  /**
   * Create actor reference for a specified local or remote path. If no such
   * actor exists, it will be (equivalent to) a dead letter reference.
   */
  def actorFor(path: ActorPath): InternalActorRef

  /**
   * Create actor reference for a specified local or remote path, which will
   * be parsed using java.net.URI. If no such actor exists, it will be
   * (equivalent to) a dead letter reference. If `s` is a relative URI, resolve
   * it relative to the given ref.
   */
  def actorFor(ref: InternalActorRef, s: String): InternalActorRef

  /**
   * Create actor reference for the specified child path starting at the
   * given starting point. This method always returns an actor which is “logically local”,
   * i.e. it cannot be used to obtain a reference to an actor which is not
   * physically or logically attached to this actor system.
   */
  def actorFor(ref: InternalActorRef, p: Iterable[String]): InternalActorRef

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
 * Interface implemented by ActorSystem and AkkaContext, the only two places from which you can get fresh actors.
 */
trait ActorRefFactory {

  protected def systemImpl: ActorSystemImpl

  protected def provider: ActorRefProvider

  protected def dispatcher: MessageDispatcher

  /**
   * Father of all children created by this interface.
   */
  protected def guardian: InternalActorRef

  protected def lookupRoot: InternalActorRef

  protected def randomName(): String

  /**
   * Create new actor as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future).
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   */
  def actorOf(props: Props): ActorRef = provider.actorOf(systemImpl, props, guardian, randomName(), false)

  /**
   * Create new actor as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * and `InvalidActorNameException` is thrown.
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   */
  def actorOf(props: Props, name: String): ActorRef

  /**
   * Create new actor of the given type as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future). The type must have
   * a no-arg constructor which will be invoked using reflection.
   */
  def actorOf[T <: Actor](implicit m: Manifest[T]): ActorRef = actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  /**
   * Create new actor of the given type as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * and `InvalidActorNameException` is thrown. The type must have
   * a no-arg constructor which will be invoked using reflection.
   */
  def actorOf[T <: Actor](name: String)(implicit m: Manifest[T]): ActorRef =
    actorOf(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), name)

  /**
   * Create new actor of the given class as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future). The class must have
   * a no-arg constructor which will be invoked using reflection.
   */
  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  /**
   * Create new actor as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future). Use this
   * method to pass constructor arguments to the [[akka.actor.Actor]] while using
   * only default [[akka.actor.Props]]; otherwise refer to `actorOf(Props)`.
   */
  def actorOf(factory: ⇒ Actor): ActorRef = actorOf(Props(() ⇒ factory))

  /**
   * ''Java API'': Create new actor as child of this context and give it an
   * automatically generated name (currently similar to base64-encoded integer
   * count, reversed and with “$” prepended, may change in the future).
   *
   * Identical to `actorOf(Props(() => creator.create()))`.
   */
  def actorOf(creator: UntypedActorFactory): ActorRef = actorOf(Props(() ⇒ creator.create()))

  /**
   * ''Java API'': Create new actor as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * and `InvalidActorNameException` is thrown.
   *
   * Identical to `actorOf(Props(() => creator.create()), name)`.
   */
  def actorOf(creator: UntypedActorFactory, name: String): ActorRef = actorOf(Props(() ⇒ creator.create()), name)

  /**
   * Look-up an actor by path; if it does not exist, returns a reference to
   * the dead-letter mailbox of the [[akka.actor.ActorSystem]]. If the path
   * point to an actor which is not local, no attempt is made during this
   * call to verify that the actor it represents does exist or is alive; use
   * `watch(ref)` to be notified of the target’s termination, which is also
   * signaled if the queried path cannot be resolved.
   */
  def actorFor(path: ActorPath): ActorRef = provider.actorFor(path)

  /**
   * Look-up an actor by path represented as string.
   *
   * Absolute URIs like `akka://appname/user/actorA` are looked up as described
   * for look-ups by `actorOf(ActorPath)`.
   *
   * Relative URIs like `/service/actorA/childB` are looked up relative to the
   * root path of the [[akka.actor.ActorSystem]] containing this factory and as
   * described for look-ups by `actorOf(Iterable[String])`.
   *
   * Relative URIs like `myChild/grandChild` or `../myBrother` are looked up
   * relative to the current context as described for look-ups by
   * `actorOf(Iterable[String])`
   */
  def actorFor(path: String): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * Look-up an actor by applying the given path elements, starting from the
   * current context, where `".."` signifies the parent of an actor.
   *
   * Example:
   * {{{
   * class MyActor extends Actor {
   *   def receive = {
   *     case msg =>
   *       ...
   *       val target = context.actorFor(Seq("..", "myBrother", "myNephew"))
   *       ...
   *   }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: Iterable[String]): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * Look-up an actor by applying the given path elements, starting from the
   * current context, where `".."` signifies the parent of an actor.
   *
   * Example:
   * {{{
   * public class MyActor extends UntypedActor {
   *   public void onReceive(Object msg) throws Exception {
   *     ...
   *     final List<String> path = new ArrayList<String>();
   *     path.add("..");
   *     path.add("myBrother");
   *     path.add("myNephew");
   *     final ActorRef target = context().actorFor(path);
   *     ...
   *   }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: java.util.List[String]): ActorRef = {
    import scala.collection.JavaConverters._
    provider.actorFor(lookupRoot, path.asScala)
  }

  /**
   * Construct an [[akka.actor.ActorSelection]] from the given path, which is
   * parsed for wildcards (these are replaced by regular expressions
   * internally). No attempt is made to verify the existence of any part of
   * the supplied path, it is recommended to send a message and gather the
   * replies in order to resolve the matching set of actors.
   */
  def actorSelection(path: String): ActorSelection = ActorSelection(lookupRoot, path)
}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Internal Akka use only, used in implementation of system.actorOf.
 */
private[akka] case class CreateChild(props: Props, name: String)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(
  _systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  val deadLetters: InternalActorRef) extends ActorRefProvider {

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

  private def tempName() = Helpers.base64(tempNumber.getAndIncrement())

  private val tempNode = rootPath / "temp"

  def tempPath() = tempNode / tempName()

  /**
   * Top-level anchor for the supervision hierarchy of this actor system. Will
   * receive only Supervise/ChildTerminated system messages or Failure message.
   */
  private[akka] val theOneWhoWalksTheBubblesOfSpaceTime: InternalActorRef = new MinimalActorRef {
    val stopped = new Switch(false)

    @volatile
    var causeOfTermination: Option[Throwable] = None

    // FIXME (actor path): move the root path to the new root guardian
    val path = rootPath / "bubble-walker"

    val address = path.toString

    override def stop() = stopped switchOn {
      terminationFuture.complete(causeOfTermination.toLeft(()))
    }

    override def isTerminated = stopped.isOn

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = stopped.ifOff(message match {
      case Failed(ex) if sender ne null ⇒ causeOfTermination = Some(ex); sender.stop()
      case _                            ⇒ log.error(this + " received unexpected message " + message)
    })

    override def sendSystemMessage(message: SystemMessage): Unit = stopped ifOff {
      message match {
        case Supervise(child)       ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case ChildTerminated(child) ⇒ stop()
        case _                      ⇒ log.error(this + " received unexpected system message " + message)
      }
    }
  }

  private class Guardian extends Actor {
    def receive = {
      case Terminated(_)            ⇒ context.self.stop()
      case CreateChild(child, name) ⇒ sender ! (try context.actorOf(child, name) catch { case e: Exception ⇒ e })
      case m                        ⇒ deadLetters ! DeadLetter(m, sender, self)
    }
  }

  private class SystemGuardian extends Actor {
    def receive = {
      case Terminated(_) ⇒
        eventStream.stopDefaultLoggers()
        context.self.stop()
      case CreateChild(child, name) ⇒ sender ! (try context.actorOf(child, name) catch { case e: Exception ⇒ e })
      case m                        ⇒ deadLetters ! DeadLetter(m, sender, self)
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

  lazy val rootGuardian: InternalActorRef = new LocalActorRef(system, guardianProps, theOneWhoWalksTheBubblesOfSpaceTime, rootPath, true) {
    override def getParent: InternalActorRef = this
    override def getSingleChild(name: String): InternalActorRef = {
      name match {
        case "temp" ⇒ tempContainer
        case _      ⇒ super.getSingleChild(name)
      }
    }
  }

  lazy val guardian: InternalActorRef = actorOf(system, guardianProps, rootGuardian, "user", true)

  lazy val systemGuardian: InternalActorRef = actorOf(system, guardianProps.withCreator(new SystemGuardian), rootGuardian, "system", true)

  lazy val tempContainer = new MinimalActorRef {
    val children = new ConcurrentHashMap[String, AskActorRef]
    def path = tempNode
    override def getParent = rootGuardian
    override def getChild(name: Iterable[String]): InternalActorRef = {
      children.get(name.head) match {
        case null ⇒ Nobody
        case some ⇒
          val t = name.tail
          if (t.isEmpty) some
          else some.getChild(t)
      }
    }
  }

  val deathWatch = createDeathWatch()

  def init(_system: ActorSystemImpl) {
    system = _system
    // chain death watchers so that killing guardian stops the application
    deathWatch.subscribe(systemGuardian, guardian)
    deathWatch.subscribe(rootGuardian, systemGuardian)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case RelativeActorPath(elems) ⇒
      if (elems.isEmpty) deadLetters
      else if (elems.head.isEmpty) actorFor(rootGuardian, elems.tail)
      else actorFor(ref, elems)
    case LocalActorPath(address, elems) if address == rootPath.address ⇒ actorFor(rootGuardian, elems)
    case _ ⇒ deadLetters
  }

  def actorFor(path: ActorPath): InternalActorRef =
    if (path.root == rootPath) actorFor(rootGuardian, path.elements)
    else deadLetters

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef =
    if (path.isEmpty) deadLetters
    else ref.getChild(path) match {
      case Nobody ⇒ deadLetters
      case x      ⇒ x
    }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, name: String, systemService: Boolean): InternalActorRef = {
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

        actorOf(system, RoutedProps(routerFactory = routerFactory, connectionManager = new LocalConnectionManager(connections)), supervisor, path.name)

      case unknown ⇒ throw new Exception("Don't know how to create this actor ref! Why? Got: " + unknown)
    }
  }

  /**
   * Creates (or fetches) a routed actor reference, configured by the 'props: RoutedProps' configuration.
   */
  def actorOf(system: ActorSystem, props: RoutedProps, supervisor: InternalActorRef, name: String): InternalActorRef = {
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

  private[akka] def createDeathWatch(): DeathWatch = new LocalDeathWatch

  private[akka] def ask(message: Any, recipient: ActorRef, within: Timeout): Future[Any] = {
    import akka.dispatch.DefaultPromise
    (if (within == null) settings.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒
        new DefaultPromise[Any](0)(dispatcher) //Abort early if nonsensical timeout
      case t ⇒
        val path = tempPath()
        val name = path.name
        val a = new AskActorRef(path, tempContainer, deathWatch, t, dispatcher) {
          override def whenDone() {
            tempContainer.children.remove(name)
          }
        }
        tempContainer.children.put(name, a)
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

/**
 * Scheduled tasks (Runnable and functions) are executed with the supplied dispatcher.
 * Note that dispatcher is by-name parameter, because dispatcher might not be initialized
 * when the scheduler is created.
 */
class DefaultScheduler(hashedWheelTimer: HashedWheelTimer, log: LoggingAdapter, dispatcher: ⇒ MessageDispatcher) extends Scheduler with Closeable {
  import org.jboss.netty.akka.util.{ Timeout ⇒ HWTimeout }

  def schedule(initialDelay: Duration, delay: Duration, receiver: ActorRef, message: Any): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(delay, receiver, message), initialDelay))

  def schedule(initialDelay: Duration, delay: Duration)(f: ⇒ Unit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(delay, f), initialDelay))

  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(runnable), delay))

  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(receiver, message), delay))

  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(f), delay))

  private def createSingleTask(runnable: Runnable): TimerTask =
    new TimerTask() {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        dispatcher.dispatchTask(() ⇒ runnable.run())
      }
    }

  private def createSingleTask(receiver: ActorRef, message: Any): TimerTask =
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        receiver ! message
      }
    }

  private def createSingleTask(f: ⇒ Unit): TimerTask =
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        dispatcher.dispatchTask(() ⇒ f)
      }
    }

  private def createContinuousTask(delay: Duration, receiver: ActorRef, message: Any): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        // Check if the receiver is still alive and kicking before sending it a message and reschedule the task
        if (!receiver.isTerminated) {
          receiver ! message
          try timeout.getTimer.newTimeout(this, delay) catch {
            case _: IllegalStateException ⇒ // stop recurring if timer is stopped
          }
        } else {
          log.warning("Could not reschedule message to be sent because receiving actor has been terminated.")
        }
      }
    }
  }

  private def createContinuousTask(delay: Duration, f: ⇒ Unit): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        dispatcher.dispatchTask(() ⇒ f)
        try timeout.getTimer.newTimeout(this, delay) catch {
          case _: IllegalStateException ⇒ // stop recurring if timer is stopped
        }
      }
    }
  }

  private def execDirectly(t: HWTimeout): Unit = {
    try t.getTask.run(t) catch {
      case e: InterruptedException ⇒ throw e
      case e: Exception            ⇒ log.error(e, "exception while executing timer task")
    }
  }

  def close() = {
    import scala.collection.JavaConverters._
    hashedWheelTimer.stop().asScala foreach execDirectly
  }
}

class DefaultCancellable(val timeout: org.jboss.netty.akka.util.Timeout) extends Cancellable {
  def cancel() {
    timeout.cancel()
  }

  def isCancelled: Boolean = {
    timeout.isCancelled
  }
}

