/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.util.concurrent.atomic.AtomicLong
import akka.dispatch._
import akka.routing._
import akka.AkkaException
import akka.util.{ Switch, Helpers }
import akka.event._

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
   * Dead letter destination for this provider.
   */
  def deadLetters: ActorRef

  /**
   * Reference to the death watch service.
   */
  def deathWatch: DeathWatch

  /**
   * Care-taker of actor refs which await final termination but cannot be kept
   * in their parent’s children list because the name shall be freed.
   */
  def locker: Locker

  /**
   * The root path for all actors within this actor system, including remote
   * address if enabled.
   */
  def rootPath: ActorPath

  def settings: ActorSystem.Settings

  def dispatcher: MessageDispatcher

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
   * Generates and returns a unique actor path below “/temp”.
   */
  def tempPath(): ActorPath

  /**
   * Returns the actor reference representing the “/temp” path.
   */
  def tempContainer: InternalActorRef

  /**
   * Registers an actorRef at a path returned by tempPath(); do NOT pass in any other path.
   */
  def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit

  /**
   * Unregister a temporary actor from the “/temp” path (i.e. obtained from tempPath()); do NOT pass in any other path.
   */
  def unregisterTempActor(path: ActorPath): Unit

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
  val deployer: Deployer) extends ActorRefProvider {

  // this is the constructor needed for reflectively instantiating the provider
  def this(_systemName: String,
           settings: ActorSystem.Settings,
           eventStream: EventStream,
           scheduler: Scheduler) =
    this(_systemName,
      settings,
      eventStream,
      scheduler,
      new Deployer(settings))

  val rootPath: ActorPath = RootActorPath(Address("akka", _systemName))

  val log = Logging(eventStream, "LocalActorRefProvider(" + rootPath.address + ")")

  val deadLetters = new DeadLetterActorRef(this, rootPath / "deadLetters", eventStream)

  val deathWatch = new LocalDeathWatch(1024) //TODO make configrable

  val locker: Locker = new Locker(scheduler, settings.ReaperInterval, this, rootPath / "locker", deathWatch)

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

    def provider: ActorRefProvider = LocalActorRefProvider.this

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

    override val supervisorStrategy = {
      import akka.actor.SupervisorStrategy._
      OneForOneStrategy() {
        case _: ActorKilledException         ⇒ Stop
        case _: ActorInitializationException ⇒ Stop
        case _: Exception                    ⇒ Restart
      }
    }

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

  private val guardianProps = Props(new Guardian)

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

  lazy val tempContainer = new VirtualPathContainer(system.provider, tempNode, rootGuardian, log)

  def registerTempActor(actorRef: InternalActorRef, path: ActorPath): Unit = {
    assert(path.parent eq tempNode, "cannot registerTempActor() with anything not obtained from tempPath()")
    tempContainer.addChild(path.name, actorRef)
  }

  def unregisterTempActor(path: ActorPath): Unit = {
    assert(path.parent eq tempNode, "cannot unregisterTempActor() with anything not obtained from tempPath()")
    tempContainer.removeChild(path.name)
  }

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
    case ActorPathExtractor(address, elems) if address == rootPath.address ⇒ actorFor(rootGuardian, elems)
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
        new EmptyLocalActorRef(system.provider, ref.path / path, eventStream)
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

