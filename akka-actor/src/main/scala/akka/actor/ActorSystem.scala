/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.config.ConfigurationException
import akka.event._
import akka.dispatch._
import akka.pattern.ask
import org.jboss.netty.akka.util.HashedWheelTimer
import java.util.concurrent.TimeUnit.MILLISECONDS
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.annotation.tailrec
import org.jboss.netty.akka.util.internal.ConcurrentIdentityHashMap
import java.io.Closeable
import akka.dispatch.Await.Awaitable
import akka.dispatch.Await.CanAwait
import java.util.concurrent.{ CountDownLatch, TimeoutException, RejectedExecutionException }
import akka.util._
import collection.immutable.Stack

object ActorSystem {

  val Version = "2.0.2-SNAPSHOT"

  val EnvHome = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val SystemHome = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GlobalHome = SystemHome orElse EnvHome

  /**
   * Creates a new ActorSystem with the name "default",
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def create(): ActorSystem = apply()

  /**
   * Creates a new ActorSystem with the specified name,
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def create(name: String): ActorSystem = apply(name)

  /**
   * Creates a new ActorSystem with the name "default", and the specified Config, then
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   */
  def create(name: String, config: Config): ActorSystem = apply(name, config)

  /**
   * Creates a new ActorSystem with the name "default", the specified Config, and specified ClassLoader
   */
  def create(name: String, config: Config, classLoader: ClassLoader): ActorSystem = apply(name, config, classLoader)

  /**
   * Creates a new ActorSystem with the name "default",
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def apply(): ActorSystem = apply("default")

  /**
   * Creates a new ActorSystem with the specified name,
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   * Then it loads the default reference configuration using the ClassLoader.
   */
  def apply(name: String): ActorSystem = {
    val classLoader = findClassLoader()
    apply(name, ConfigFactory.load(classLoader), classLoader)
  }

  /**
   * Creates a new ActorSystem with the name "default", and the specified Config, then
   * obtains the current ClassLoader by first inspecting the current threads' getContextClassLoader,
   * then tries to walk the stack to find the callers class loader, then falls back to the ClassLoader
   * associated with the ActorSystem class.
   */
  def apply(name: String, config: Config): ActorSystem = apply(name, config, findClassLoader())

  /**
   * Creates a new ActorSystem with the name "default", the specified Config, and specified ClassLoader
   */
  def apply(name: String, config: Config, classLoader: ClassLoader): ActorSystem = new ActorSystemImpl(name, config, classLoader).start()

  class Settings(classLoader: ClassLoader, cfg: Config, final val name: String) {

    final val config: Config = {
      val config = cfg.withFallback(ConfigFactory.defaultReference(classLoader))
      config.checkValid(ConfigFactory.defaultReference(classLoader), "akka")
      config
    }

    import scala.collection.JavaConverters._
    import config._

    final val ConfigVersion = getString("akka.version")

    final val ProviderClass = getString("akka.actor.provider")

    final val CreationTimeout = Timeout(Duration(getMilliseconds("akka.actor.creation-timeout"), MILLISECONDS))
    final val ReaperInterval = Duration(getMilliseconds("akka.actor.reaper-interval"), MILLISECONDS)
    final val SerializeAllMessages = getBoolean("akka.actor.serialize-messages")
    final val SerializeAllCreators = getBoolean("akka.actor.serialize-creators")

    final val LogLevel = getString("akka.loglevel")
    final val StdoutLogLevel = getString("akka.stdout-loglevel")
    final val EventHandlers: Seq[String] = getStringList("akka.event-handlers").asScala
    final val EventHandlerStartTimeout = Timeout(Duration(getMilliseconds("akka.event-handler-startup-timeout"), MILLISECONDS))
    final val LogConfigOnStart = config.getBoolean("akka.log-config-on-start")
    
    final val AddLoggingReceive = getBoolean("akka.actor.debug.receive")
    final val DebugAutoReceive = getBoolean("akka.actor.debug.autoreceive")
    final val DebugLifecycle = getBoolean("akka.actor.debug.lifecycle")
    final val FsmDebugEvent = getBoolean("akka.actor.debug.fsm")
    final val DebugEventStream = getBoolean("akka.actor.debug.event-stream")

    final val Home = config.getString("akka.home") match {
      case "" ⇒ None
      case x  ⇒ Some(x)
    }

    final val SchedulerTickDuration = Duration(getMilliseconds("akka.scheduler.tick-duration"), MILLISECONDS)
    final val SchedulerTicksPerWheel = getInt("akka.scheduler.ticks-per-wheel")
    final val Daemonicity = getBoolean("akka.daemonic")
    final val JvmExitOnFatalError = getBoolean("akka.jvm-exit-on-fatal-error")

    if (ConfigVersion != Version)
      throw new ConfigurationException("Akka JAR version [" + Version + "] does not match the provided config version [" + ConfigVersion + "]")

    override def toString: String = config.root.render
  }

  /**
   * INTERNAL
   */
  private[akka] def findClassLoader(): ClassLoader = {
    def findCaller(get: Int ⇒ Class[_]): ClassLoader =
      Iterator.from(2 /*is the magic number, promise*/ ).map(get) dropWhile { c ⇒
        c != null &&
          (c.getName.startsWith("akka.actor.ActorSystem") ||
            c.getName.startsWith("scala.Option") ||
            c.getName.startsWith("scala.collection.Iterator") ||
            c.getName.startsWith("akka.util.Reflect"))
      } next () match {
        case null ⇒ getClass.getClassLoader
        case c    ⇒ c.getClassLoader
      }

    Option(Thread.currentThread.getContextClassLoader) orElse
      (Reflect.getCallerClass map findCaller) getOrElse
      getClass.getClassLoader
  }
}

/**
 * An actor system is a hierarchical group of actors which share common
 * configuration, e.g. dispatchers, deployments, remote capabilities and
 * addresses. It is also the entry point for creating or looking up actors.
 *
 * There are several possibilities for creating actors (see [[akka.actor.Props]]
 * for details on `props`):
 *
 * {{{
 * // Java or Scala
 * system.actorOf(props, "name")
 * system.actorOf(props)
 *
 * // Scala
 * system.actorOf(Props[MyActor], "name")
 * system.actorOf(Props[MyActor])
 * system.actorOf(Props(new MyActor(...)))
 *
 * // Java
 * system.actorOf(MyActor.class);
 * system.actorOf(Props(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * });
 * system.actorOf(Props(new Creator<MyActor>() {
 *   public MyActor create() { ... }
 * }, "name");
 * }}}
 *
 * Where no name is given explicitly, one will be automatically generated.
 *
 * <b><i>Important Notice:</i></o>
 *
 * This class is not meant to be extended by user code. If you want to
 * actually roll your own Akka, it will probably be better to look into
 * extending [[akka.actor.ExtendedActorSystem]] instead, but beware that you
 * are completely on your own in that case!
 */
abstract class ActorSystem extends ActorRefFactory {
  import ActorSystem._

  /**
   * The name of this actor system, used to distinguish multiple ones within
   * the same JVM & class loader.
   */
  def name: String

  /**
   * The core settings extracted from the supplied configuration.
   */
  def settings: Settings

  /**
   * Log the configuration.
   */
  def logConfiguration(): Unit

  /**
   * Construct a path below the application guardian to be used with [[ActorSystem.actorFor]].
   */
  def /(name: String): ActorPath

  /**
   * ''Java API'': Create a new child actor path.
   */
  def child(child: String): ActorPath = /(child)

  /**
   * Construct a path below the application guardian to be used with [[ActorSystem.actorFor]].
   */
  def /(name: Iterable[String]): ActorPath

  /**
   * ''Java API'': Recursively create a descendant’s path by appending all child names.
   */
  def descendant(names: java.lang.Iterable[String]): ActorPath = {
    import scala.collection.JavaConverters._
    /(names.asScala)
  }

  /**
   * Start-up time in milliseconds since the epoch.
   */
  val startTime: Long = System.currentTimeMillis

  /**
   * Up-time of this actor system in seconds.
   */
  def uptime: Long = (System.currentTimeMillis - startTime) / 1000

  /**
   * Main event bus of this actor system, used for example for logging.
   */
  def eventStream: EventStream

  /**
   * Convenient logging adapter for logging to the [[ActorSystem.eventStream]].
   */
  def log: LoggingAdapter

  /**
   * Actor reference where messages are re-routed to which were addressed to
   * stopped or non-existing actors. Delivery to this actor is done on a best
   * effort basis and hence not strictly guaranteed.
   */
  def deadLetters: ActorRef
  //#scheduler
  /**
   * Light-weight scheduler for running asynchronous tasks after some deadline
   * in the future. Not terribly precise but cheap.
   */
  def scheduler: Scheduler
  //#scheduler

  /**
   * Helper object for looking up configured dispatchers.
   */
  def dispatchers: Dispatchers

  /**
   * Default dispatcher as configured. This dispatcher is used for all actors
   * in the actor system which do not have a different dispatcher configured
   * explicitly.
   */
  def dispatcher: MessageDispatcher

  /**
   * Register a block of code (callback) to run after all actors in this actor system have
   * been stopped. Multiple code blocks may be registered by calling this method multiple times.
   * The callbacks will be run sequentially in reverse order of registration, i.e.
   * last registration is run first.
   *
   * @throws a RejectedExecutionException if the System has already shut down or if shutdown has been initiated.
   *
   * Scala API
   */
  def registerOnTermination[T](code: ⇒ T): Unit

  /**
   * Register a block of code (callback) to run after all actors in this actor system have
   * been stopped. Multiple code blocks may be registered by calling this method multiple times.
   * The callbacks will be run sequentially in reverse order of registration, i.e.
   * last registration is run first.
   *
   * @throws a RejectedExecutionException if the System has already shut down or if shutdown has been initiated.
   *
   * Java API
   */
  def registerOnTermination(code: Runnable): Unit

  /**
   * Block current thread until the system has been shutdown, or the specified
   * timeout has elapsed. This will block until after all on termination
   * callbacks have been run.
   *
   * @throws TimeoutException in case of timeout
   */
  def awaitTermination(timeout: Duration): Unit

  /**
   * Block current thread until the system has been shutdown. This will
   * block until after all on termination callbacks have been run.
   */
  def awaitTermination(): Unit

  /**
   * Stop this actor system. This will stop the guardian actor, which in turn
   * will recursively stop all its child actors, then the system guardian
   * (below which the logging actors reside) and the execute all registered
   * termination handlers (see [[ActorSystem.registerOnTermination]]).
   */
  def shutdown(): Unit

  /**
   * Query the termination status: if it returns true, all callbacks have run
   * and the ActorSystem has been fully stopped, i.e.
   * `awaitTermination(0 seconds)` would return normally. If this method
   * returns `false`, the status is actually unknown, since it might have
   * changed since you queried it.
   */
  def isTerminated: Boolean

  /**
   * Registers the provided extension and creates its payload, if this extension isn't already registered
   * This method has putIfAbsent-semantics, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def registerExtension[T <: Extension](ext: ExtensionId[T]): T

  /**
   * Returns the payload that is associated with the provided extension
   * throws an IllegalStateException if it is not registered.
   * This method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def extension[T <: Extension](ext: ExtensionId[T]): T

  /**
   * Returns whether the specified extension is already registered, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean
}

/**
 * More powerful interface to the actor system’s implementation which is presented to extensions (see [[akka.actor.Extension]]).
 *
 * <b><i>Important Notice:</i></o>
 *
 * This class is not meant to be extended by user code. If you want to
 * actually roll your own Akka, beware that you are completely on your own in
 * that case!
 */
abstract class ExtendedActorSystem extends ActorSystem {

  /**
   * The ActorRefProvider is the only entity which creates all actor references within this actor system.
   */
  def provider: ActorRefProvider

  /**
   * The top-level supervisor of all actors created using system.actorOf(...).
   */
  def guardian: InternalActorRef

  /**
   * The top-level supervisor of all system-internal services like logging.
   */
  def systemGuardian: InternalActorRef

  /**
   * Implementation of the mechanism which is used for watch()/unwatch().
   */
  def deathWatch: DeathWatch

  /**
   * ClassLoader wrapper which is used for reflective accesses internally. This is set
   * to use the context class loader, if one is set, or the class loader which
   * loaded the ActorSystem implementation. The context class loader is also
   * set on all threads created by the ActorSystem, if one was set during
   * creation.
   */
  def dynamicAccess: DynamicAccess
}

class ActorSystemImpl protected[akka] (val name: String, applicationConfig: Config, classLoader: ClassLoader) extends ExtendedActorSystem {

  if (!name.matches("""^[a-zA-Z0-9][a-zA-Z0-9-]*$"""))
    throw new IllegalArgumentException(
      "invalid ActorSystem name [" + name +
        "], must contain only word characters (i.e. [a-zA-Z_0-9] plus non-leading '-')")

  import ActorSystem._

  final val settings: Settings = new Settings(classLoader, applicationConfig, name)

  protected def uncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() {
      def uncaughtException(thread: Thread, cause: Throwable): Unit = {
        cause match {
          case NonFatal(_) | _: InterruptedException ⇒ log.error(cause, "Uncaught error from thread [{}]", thread.getName)
          case _ ⇒
            if (settings.JvmExitOnFatalError) {
              try {
                log.error(cause, "Uncaught error from thread [{}] shutting down JVM since 'akka.jvm-exit-on-fatal-error' is enabled", thread.getName)
                import System.err
                err.print("Uncaught error from thread [")
                err.print(thread.getName)
                err.print("] shutting down JVM since 'akka.jvm-exit-on-fatal-error' is enabled for ActorSystem[")
                err.print(name)
                err.println("]")
                cause.printStackTrace(System.err)
                System.err.flush()
              } finally {
                System.exit(-1)
              }
            } else {
              log.error(cause, "Uncaught fatal error from thread [{}] shutting down ActorSystem [{}]", thread.getName, name)
              shutdown()
            }
        }
      }
    }

  final val threadFactory: MonitorableThreadFactory =
    MonitorableThreadFactory(name, settings.Daemonicity, Option(classLoader), uncaughtExceptionHandler)

  /**
   * This is an extension point: by overriding this method, subclasses can
   * control all reflection activities of an actor system.
   */
  protected def createDynamicAccess(): DynamicAccess = new ReflectiveDynamicAccess(classLoader)

  private val _pm: DynamicAccess = createDynamicAccess()
  def dynamicAccess: DynamicAccess = _pm

  def logConfiguration(): Unit = log.info(settings.toString)

  protected def systemImpl = this

  private[akka] def systemActorOf(props: Props, name: String): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    Await.result(systemGuardian ? CreateChild(props, name), timeout.duration) match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  def actorOf(props: Props, name: String): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    Await.result(guardian ? CreateChild(props, name), timeout.duration) match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  def actorOf(props: Props): ActorRef = {
    implicit val timeout = settings.CreationTimeout
    Await.result(guardian ? CreateRandomNameChild(props), timeout.duration) match {
      case ref: ActorRef ⇒ ref
      case ex: Exception ⇒ throw ex
    }
  }

  def stop(actor: ActorRef): Unit = {
    implicit val timeout = settings.CreationTimeout
    val path = actor.path
    val guard = guardian.path
    val sys = systemGuardian.path
    path.parent match {
      case `guard` ⇒ Await.result(guardian ? StopChild(actor), timeout.duration)
      case `sys`   ⇒ Await.result(systemGuardian ? StopChild(actor), timeout.duration)
      case _       ⇒ actor.asInstanceOf[InternalActorRef].stop()
    }
  }

  import settings._

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream: EventStream = new EventStream(DebugEventStream)
  eventStream.startStdoutLogger(settings)

  val log: LoggingAdapter = new BusLogging(eventStream, "ActorSystem(" + name + ")", this.getClass)

  val scheduler: Scheduler = createScheduler()

  val provider: ActorRefProvider = {
    val arguments = Seq(
      classOf[String] -> name,
      classOf[Settings] -> settings,
      classOf[EventStream] -> eventStream,
      classOf[Scheduler] -> scheduler,
      classOf[DynamicAccess] -> dynamicAccess)

    dynamicAccess.createInstanceFor[ActorRefProvider](ProviderClass, arguments) match {
      case Left(e)  ⇒ throw e
      case Right(p) ⇒ p
    }
  }

  def deadLetters: ActorRef = provider.deadLetters

  val deadLetterQueue: MessageQueue = new MessageQueue {
    def enqueue(receiver: ActorRef, envelope: Envelope) { deadLetters ! DeadLetter(envelope.message, envelope.sender, receiver) }
    def dequeue() = null
    def hasMessages = false
    def numberOfMessages = 0
    def cleanUp(owner: ActorContext, deadLetters: MessageQueue): Unit = ()
  }

  val deadLetterMailbox: Mailbox = new Mailbox(null, deadLetterQueue) {
    becomeClosed()
    def systemEnqueue(receiver: ActorRef, handle: SystemMessage): Unit = deadLetters ! DeadLetter(handle, receiver, receiver)
    def systemDrain(): SystemMessage = null
    def hasSystemMessages = false
  }

  val dispatchers: Dispatchers = new Dispatchers(settings, DefaultDispatcherPrerequisites(
    threadFactory, eventStream, deadLetterMailbox, scheduler, dynamicAccess, settings))

  val dispatcher: MessageDispatcher = dispatchers.defaultGlobalDispatcher

  def terminationFuture: Future[Unit] = provider.terminationFuture
  def lookupRoot: InternalActorRef = provider.rootGuardian
  def guardian: InternalActorRef = provider.guardian
  def systemGuardian: InternalActorRef = provider.systemGuardian
  def deathWatch: DeathWatch = provider.deathWatch

  def /(actorName: String): ActorPath = guardian.path / actorName
  def /(path: Iterable[String]): ActorPath = guardian.path / path

  private lazy val _start: this.type = {
    // the provider is expected to start default loggers, LocalActorRefProvider does this
    provider.init(this)
    registerOnTermination(stopScheduler())
    loadExtensions()
    if (LogConfigOnStart) logConfiguration()
    this
  }

  def start(): this.type = _start

  private lazy val terminationCallbacks = {
    val callbacks = new TerminationCallbacks
    terminationFuture onComplete (_ ⇒ callbacks.run)
    callbacks
  }
  def registerOnTermination[T](code: ⇒ T) { registerOnTermination(new Runnable { def run = code }) }
  def registerOnTermination(code: Runnable) { terminationCallbacks.add(code) }
  def awaitTermination(timeout: Duration) { Await.ready(terminationCallbacks, timeout) }
  def awaitTermination() = awaitTermination(Duration.Inf)
  def isTerminated = terminationCallbacks.isTerminated

  def shutdown(): Unit = guardian.stop()

  /**
   * Create the scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   *
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
  protected def createScheduler(): Scheduler = {
    val hwt = new HashedWheelTimer(log,
      threadFactory.copy(threadFactory.name + "-scheduler"),
      settings.SchedulerTickDuration,
      settings.SchedulerTicksPerWheel)
    // note that dispatcher is by-name parameter in DefaultScheduler constructor,
    // because dispatcher is not initialized when the scheduler is created
    def safeDispatcher = dispatcher match {
      case null ⇒
        val exc = new IllegalStateException("Scheduler is using dispatcher before it has been initialized")
        log.error(exc, exc.getMessage)
        throw exc
      case dispatcher ⇒ dispatcher
    }
    new DefaultScheduler(hwt, log, safeDispatcher)
  }

  /*
   * This is called after the last actor has signaled its termination, i.e.
   * after the last dispatcher has had its chance to schedule its shutdown
   * action.
   */
  protected def stopScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  private val extensions = new ConcurrentIdentityHashMap[ExtensionId[_], AnyRef]

  /**
   * Returns any extension registered to the specified Extension or returns null if not registered
   */
  @tailrec
  private def findExtension[T <: Extension](ext: ExtensionId[T]): T = extensions.get(ext) match {
    case c: CountDownLatch ⇒ c.await(); findExtension(ext) //Registration in process, await completion and retry
    case other             ⇒ other.asInstanceOf[T] //could be a T or null, in which case we return the null as T
  }

  @tailrec
  final def registerExtension[T <: Extension](ext: ExtensionId[T]): T = {
    findExtension(ext) match {
      case null ⇒ //Doesn't already exist, commence registration
        val inProcessOfRegistration = new CountDownLatch(1)
        extensions.putIfAbsent(ext, inProcessOfRegistration) match { // Signal that registration is in process
          case null ⇒ try { // Signal was successfully sent
            ext.createExtension(this) match { // Create and initialize the extension
              case null ⇒ throw new IllegalStateException("Extension instance created as 'null' for extension [" + ext + "]")
              case instance ⇒
                extensions.replace(ext, inProcessOfRegistration, instance) //Replace our in process signal with the initialized extension
                instance //Profit!
            }
          } catch {
            case t ⇒
              extensions.remove(ext, inProcessOfRegistration) //In case shit hits the fan, remove the inProcess signal
              throw t //Escalate to caller
          } finally {
            inProcessOfRegistration.countDown //Always notify listeners of the inProcess signal
          }
          case other ⇒ registerExtension(ext) //Someone else is in process of registering an extension for this Extension, retry
        }
      case existing ⇒ existing.asInstanceOf[T]
    }
  }

  def extension[T <: Extension](ext: ExtensionId[T]): T = findExtension(ext) match {
    case null ⇒ throw new IllegalArgumentException("Trying to get non-registered extension [" + ext + "]")
    case some ⇒ some.asInstanceOf[T]
  }

  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean = findExtension(ext) != null

  private def loadExtensions() {
    import scala.collection.JavaConversions._
    settings.config.getStringList("akka.extensions") foreach { fqcn ⇒
      dynamicAccess.getObjectFor[AnyRef](fqcn).fold(_ ⇒ dynamicAccess.createInstanceFor[AnyRef](fqcn, Seq()), Right(_)) match {
        case Right(p: ExtensionIdProvider) ⇒ registerExtension(p.lookup());
        case Right(p: ExtensionId[_])      ⇒ registerExtension(p);
        case Right(other)                  ⇒ log.error("[{}] is not an 'ExtensionIdProvider' or 'ExtensionId', skipping...", fqcn)
        case Left(problem)                 ⇒ log.error(problem, "While trying to load extension [{}], skipping...", fqcn)
      }

    }
  }

  override def toString: String = lookupRoot.path.root.address.toString

  final class TerminationCallbacks extends Runnable with Awaitable[Unit] {
    private val lock = new ReentrantGuard
    private var callbacks: Stack[Runnable] = _ //non-volatile since guarded by the lock
    lock withGuard { callbacks = Stack.empty[Runnable] }

    private val latch = new CountDownLatch(1)

    final def add(callback: Runnable): Unit = {
      latch.getCount match {
        case 0 ⇒ throw new RejectedExecutionException("Must be called prior to system shutdown.")
        case _ ⇒ lock withGuard {
          if (latch.getCount == 0) throw new RejectedExecutionException("Must be called prior to system shutdown.")
          else callbacks = callbacks.push(callback)
        }
      }
    }

    final def run(): Unit = lock withGuard {
      @tailrec def runNext(c: Stack[Runnable]): Stack[Runnable] = c.headOption match {
        case None ⇒ Stack.empty[Runnable]
        case Some(callback) ⇒
          try callback.run() catch { case e ⇒ log.error(e, "Failed to run termination callback, due to [{}]", e.getMessage) }
          runNext(c.pop)
      }
      try { callbacks = runNext(callbacks) } finally latch.countDown()
    }

    final def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
      if (atMost.isFinite()) {
        if (!latch.await(atMost.length, atMost.unit))
          throw new TimeoutException("Await termination timed out after [%s]" format (atMost.toString))
      } else latch.await()

      this
    }

    final def result(atMost: Duration)(implicit permit: CanAwait): Unit = ready(atMost)

    final def isTerminated: Boolean = latch.getCount == 0
  }
}
