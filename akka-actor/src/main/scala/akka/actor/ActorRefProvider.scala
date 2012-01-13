/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.util.concurrent.atomic.AtomicLong
import org.jboss.netty.akka.util.{ TimerTask, HashedWheelTimer }
import akka.util.Timeout.intToTimeout
import akka.config.ConfigurationException
import akka.dispatch._
import akka.routing._
import akka.AkkaException
import akka.util.{ Duration, Switch, Helpers, Timeout }
import akka.event._
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

  def deployer: Deployer

  def scheduler: Scheduler

  /**
   * Actor factory with create-only semantics: will create an actor as
   * described by props with the given supervisor and path (may be different
   * in case of remote supervision). If systemService is true, deployment is
   * bypassed (local-only).
   */
  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath, systemService: Boolean, deploy: Option[Deploy]): InternalActorRef

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

  /**
   * Create AskActorRef and register it properly so it can be serialized/deserialized;
   * caller needs to send the message.
   */
  def ask(within: Timeout): Option[AskActorRef]

  /**
   * This Future is completed upon termination of this ActorRefProvider, which
   * is usually initiated by stopping the guardian via ActorSystem.stop().
   */
  def terminationFuture: Future[Unit]
}

/**
 * Interface implemented by ActorSystem and AkkaContext, the only two places
 * from which you can get fresh actors.
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

  /**
   * Create new actor as child of this context and give it an automatically
   * generated name (currently similar to base64-encoded integer count,
   * reversed and with “$” prepended, may change in the future).
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * When invoked on ActorSystem, this method sends a message to the guardian
   * actor and blocks waiting for a reply, see `akka.actor.creation-timeout` in
   * the `reference.conf`.
   */
  def actorOf(props: Props): ActorRef

  /**
   * Create new actor as child of this context with the given name, which must
   * not be null, empty or start with “$”. If the given name is already in use,
   * and `InvalidActorNameException` is thrown.
   *
   * See [[akka.actor.Props]] for details on how to obtain a `Props` object.
   *
   * When invoked on ActorSystem, this method sends a message to the guardian
   * actor and blocks waiting for a reply, see `akka.actor.creation-timeout` in
   * the `reference.conf`.
   */
  def actorOf(props: Props, name: String): ActorRef

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
   * }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: Iterable[String]): ActorRef = provider.actorFor(lookupRoot, path)

  /**
   * ''Java API'': Look-up an actor by applying the given path elements, starting from the
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
   * }
   * }
   * }}}
   *
   * For maximum performance use a collection with efficient head & tail operations.
   */
  def actorFor(path: java.lang.Iterable[String]): ActorRef = {
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

  /**
   * Stop the actor pointed to by the given [[akka.actor.ActorRef]]; this is
   * an asynchronous operation, i.e. involves a message send, but if invoked
   * on an [[akka.actor.ActorContext]] if operating on a child of that
   * context it will free up the name for immediate reuse.
   *
   * When invoked on [[akka.actor.ActorSystem]] for a top-level actor, this
   * method sends a message to the guardian actor and blocks waiting for a reply,
   * see `akka.actor.creation-timeout` in the `reference.conf`.
   */
  def stop(actor: ActorRef): Unit
}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Internal Akka use only, used in implementation of system.actorOf.
 */
private[akka] case class CreateChild(props: Props, name: String)

/**
 * Internal Akka use only, used in implementation of system.actorOf.
 */
private[akka] case class CreateRandomNameChild(props: Props)

/**
 * Internal Akka use only, used in implementation of system.stop(child).
 */
private[akka] case class StopChild(child: ActorRef)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(
  _systemName: String,
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  val scheduler: Scheduler,
  val deadLetters: InternalActorRef,
  val rootPath: ActorPath,
  val deployer: Deployer) extends ActorRefProvider {

  def this(_systemName: String,
           settings: ActorSystem.Settings,
           eventStream: EventStream,
           scheduler: Scheduler,
           deadLetters: InternalActorRef) =
    this(_systemName,
      settings,
      eventStream,
      scheduler,
      deadLetters,
      new RootActorPath(LocalAddress(_systemName)),
      new Deployer(settings))

  // FIXME remove both
  val nodename: String = "local"
  val clustername: String = "local"

  val log = Logging(eventStream, "LocalActorRefProvider(" + rootPath.address + ")")

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

    val path = rootPath / "bubble-walker"

    override def stop() = stopped switchOn {
      terminationFuture.complete(causeOfTermination.toLeft(()))
    }

    override def isTerminated = stopped.isOn

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = stopped.ifOff(message match {
      case Failed(ex) if sender ne null ⇒ causeOfTermination = Some(ex); sender.asInstanceOf[InternalActorRef].stop()
      case _                            ⇒ log.error(this + " received unexpected message [" + message + "]")
    })

    override def sendSystemMessage(message: SystemMessage): Unit = stopped ifOff {
      message match {
        case Supervise(child)       ⇒ // TODO register child in some map to keep track of it and enable shutdown after all dead
        case ChildTerminated(child) ⇒ stop()
        case _                      ⇒ log.error(this + " received unexpected system message [" + message + "]")
      }
    }
  }

  /*
   * Guardians can be asked by ActorSystem to create children, i.e. top-level
   * actors. Therefore these need to answer to these requests, forwarding any
   * exceptions which might have occurred.
   */
  private class Guardian extends Actor {
    def receive = {
      case Terminated(_)                ⇒ context.stop(self)
      case CreateChild(child, name)     ⇒ sender ! (try context.actorOf(child, name) catch { case e: Exception ⇒ e })
      case CreateRandomNameChild(child) ⇒ sender ! (try context.actorOf(child) catch { case e: Exception ⇒ e })
      case StopChild(child)             ⇒ context.stop(child); sender ! "ok"
      case m                            ⇒ deadLetters ! DeadLetter(m, sender, self)
    }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
  }

  /*
   * Guardians can be asked by ActorSystem to create children, i.e. top-level
   * actors. Therefore these need to answer to these requests, forwarding any
   * exceptions which might have occurred.
   */
  private class SystemGuardian extends Actor {
    def receive = {
      case Terminated(_) ⇒
        eventStream.stopDefaultLoggers()
        context.stop(self)
      case CreateChild(child, name)     ⇒ sender ! (try context.actorOf(child, name) catch { case e: Exception ⇒ e })
      case CreateRandomNameChild(child) ⇒ sender ! (try context.actorOf(child) catch { case e: Exception ⇒ e })
      case StopChild(child)             ⇒ context.stop(child); sender ! "ok"
      case m                            ⇒ deadLetters ! DeadLetter(m, sender, self)
    }

    // guardian MUST NOT lose its children during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
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

  lazy val terminationFuture: Promise[Unit] = Promise[Unit]()(dispatcher)

  @volatile
  private var extraNames: Map[String, InternalActorRef] = Map()

  /**
   * Higher-level providers (or extensions) might want to register new synthetic
   * top-level paths for doing special stuff. This is the way to do just that.
   * Just be careful to complete all this before ActorSystem.start() finishes,
   * or before you start your own auto-spawned actors.
   */
  def registerExtraNames(_extras: Map[String, InternalActorRef]): Unit = extraNames ++= _extras

  lazy val rootGuardian: InternalActorRef =
    new LocalActorRef(system, guardianProps, theOneWhoWalksTheBubblesOfSpaceTime, rootPath, true) {
      object Extra {
        def unapply(s: String): Option[InternalActorRef] = extraNames.get(s)
      }

      override def getParent: InternalActorRef = this

      override def getSingleChild(name: String): InternalActorRef = {
        name match {
          case "temp"   ⇒ tempContainer
          case Extra(e) ⇒ e
          case _        ⇒ super.getSingleChild(name)
        }
      }
    }

  lazy val guardian: InternalActorRef =
    actorOf(system, guardianProps, rootGuardian, rootPath / "user", true, None)

  lazy val systemGuardian: InternalActorRef =
    actorOf(system, guardianProps.withCreator(new SystemGuardian), rootGuardian, rootPath / "system", true, None)

  lazy val tempContainer = new VirtualPathContainer(tempNode, rootGuardian, log)

  val deathWatch = new LocalDeathWatch(1024) //TODO make configrable

  def init(_system: ActorSystemImpl) {
    system = _system
    // chain death watchers so that killing guardian stops the application
    deathWatch.subscribe(systemGuardian, guardian)
    deathWatch.subscribe(rootGuardian, systemGuardian)
    eventStream.startDefaultLoggers(_system)
  }

  def actorFor(ref: InternalActorRef, path: String): InternalActorRef = path match {
    case RelativeActorPath(elems) ⇒
      if (elems.isEmpty) {
        log.debug("look-up of empty path string '{}' fails (per definition)", path)
        deadLetters
      } else if (elems.head.isEmpty) actorFor(rootGuardian, elems.tail)
      else actorFor(ref, elems)
    case LocalActorPath(address, elems) if address == rootPath.address ⇒ actorFor(rootGuardian, elems)
    case _ ⇒
      log.debug("look-up of unknown path '{}' failed", path)
      deadLetters
  }

  def actorFor(path: ActorPath): InternalActorRef =
    if (path.root == rootPath) actorFor(rootGuardian, path.elements)
    else {
      log.debug("look-up of foreign ActorPath '{}' failed", path)
      deadLetters
    }

  def actorFor(ref: InternalActorRef, path: Iterable[String]): InternalActorRef =
    if (path.isEmpty) {
      log.debug("look-up of empty path sequence fails (per definition)")
      deadLetters
    } else ref.getChild(path.iterator) match {
      case Nobody ⇒
        log.debug("look-up of path sequence '{}' failed", path)
        new EmptyLocalActorRef(eventStream, dispatcher, ref.path / path)
      case x ⇒ x
    }

  def actorOf(system: ActorSystemImpl, props: Props, supervisor: InternalActorRef, path: ActorPath, systemService: Boolean, deploy: Option[Deploy]): InternalActorRef = {
    props.routerConfig match {
      case NoRouter ⇒ new LocalActorRef(system, props, supervisor, path, systemService) // create a local actor
      case router ⇒
        val depl = deploy orElse {
          val lookupPath = path.elements.drop(1).mkString("/", "/", "")
          deployer.lookup(lookupPath)
        }
        new RoutedActorRef(system, props.withRouter(router.adaptFromDeploy(depl)), supervisor, path)
    }
  }

  def ask(within: Timeout): Option[AskActorRef] = {
    (if (within == null) settings.ActorTimeout else within) match {
      case t if t.duration.length <= 0 ⇒ None
      case t ⇒
        val path = tempPath()
        val name = path.name
        val a = new AskActorRef(path, tempContainer, dispatcher, deathWatch)
        tempContainer.addChild(name, a)
        val result = a.result
        val f = dispatcher.prerequisites.scheduler.scheduleOnce(t.duration) { result.failure(new AskTimeoutException("Timed out")) }
        result onComplete { _ ⇒
          try { a.stop(); f.cancel() }
          finally { tempContainer.removeChild(name) }
        }

        Some(a)
    }
  }
}

class LocalDeathWatch(val mapSize: Int) extends DeathWatch with ActorClassification {

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
 *
 * The HashedWheelTimer used by this class MUST throw an IllegalStateException
 * if it does not enqueue a task. Once a task is queued, it MUST be executed or
 * returned from stop().
 */
class DefaultScheduler(hashedWheelTimer: HashedWheelTimer, log: LoggingAdapter, dispatcher: ⇒ MessageDispatcher) extends Scheduler with Closeable {

  import org.jboss.netty.akka.util.{ Timeout ⇒ HWTimeout }

  def schedule(initialDelay: Duration, delay: Duration, receiver: ActorRef, message: Any): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(delay, receiver, message), initialDelay))

  def schedule(initialDelay: Duration, delay: Duration)(f: ⇒ Unit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(delay, f), initialDelay))

  def schedule(initialDelay: Duration, delay: Duration, runnable: Runnable): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createContinuousTask(delay, runnable), initialDelay))

  def scheduleOnce(delay: Duration, runnable: Runnable): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(runnable), delay))

  def scheduleOnce(delay: Duration, receiver: ActorRef, message: Any): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(receiver, message), delay))

  def scheduleOnce(delay: Duration)(f: ⇒ Unit): Cancellable =
    new DefaultCancellable(hashedWheelTimer.newTimeout(createSingleTask(f), delay))

  private def createSingleTask(runnable: Runnable): TimerTask =
    new TimerTask() {
      def run(timeout: org.jboss.netty.akka.util.Timeout) { dispatcher.execute(runnable) }
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
        dispatcher.execute(new Runnable { def run = f })
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
        dispatcher.execute(new Runnable { def run = f })
        try timeout.getTimer.newTimeout(this, delay) catch {
          case _: IllegalStateException ⇒ // stop recurring if timer is stopped
        }
      }
    }
  }

  private def createContinuousTask(delay: Duration, runnable: Runnable): TimerTask = {
    new TimerTask {
      def run(timeout: org.jboss.netty.akka.util.Timeout) {
        dispatcher.execute(runnable)
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

