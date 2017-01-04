/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import com.typesafe.config.Config
import scala.concurrent.ExecutionContext
import java.util.concurrent.ThreadFactory
import scala.concurrent.{ ExecutionContextExecutor, Future }
import akka.{ actor ⇒ a, dispatch ⇒ d, event ⇒ e }
import scala.util.control.NonFatal
import scala.util.control.ControlThrowable
import scala.collection.immutable
import akka.typed.Dispatchers
import scala.concurrent.Promise
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.util.Success
import akka.util.Timeout
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

object ActorSystemImpl {
  import ScalaDSL._

  sealed trait SystemCommand
  case class CreateSystemActor[T](behavior: Behavior[T], name: String, deployment: DeploymentConfig)(val replyTo: ActorRef[ActorRef[T]]) extends SystemCommand

  val systemGuardianBehavior: Behavior[SystemCommand] =
    ContextAware { ctx ⇒
      var i = 1
      Static {
        case create: CreateSystemActor[t] ⇒
          val name = s"$i-${create.name}"
          i += 1
          create.replyTo ! ctx.spawn(create.behavior, name, create.deployment)
      }
    }
}

/*
 * Actor Ideas:

  •  remoting/clustering is just another set of actors/extensions

Receptionist:

  •  should be a new kind of Extension (where lookup yields ActorRef)
  •  obtaining a reference may either give a single remote one or a dynamic local proxy that routes to available instances—distinguished using a “stableDestination” flag (for read-your-writes semantics)
  •  perhaps fold sharding into this: how message routing is done should not matter

Streams:

  •  make new implementation of ActorMaterializer that leverages Envelope removal
  •  all internal actor creation must be asynchronous
  •  could offer ActorSystem extension for materializer
  •  remove downcasts to ActorMaterializer in akka-stream package—replace by proper function passing or Materializer APIs where needed (should make Gearpump happier as well)
  •  add new Sink/Source for ActorRef[]

Distributed Data:

  •  create new Behaviors around the logic

 *
 */

private[typed] class ActorSystemImpl[-T](
  override val name:       String,
  _config:                 Config,
  _cl:                     ClassLoader,
  _ec:                     Option[ExecutionContext],
  _userGuardianBehavior:   Behavior[T],
  _userGuardianDeployment: DeploymentConfig)
  extends ActorRef[T](a.RootActorPath(a.Address("akka", name)) / "user") with ActorSystem[T] with ActorRefImpl[T] {

  import ActorSystemImpl._

  if (!name.matches("""^[a-zA-Z0-9][a-zA-Z0-9-_]*$"""))
    throw new IllegalArgumentException(
      "invalid ActorSystem name [" + name +
        "], must contain only word characters (i.e. [a-zA-Z0-9] plus non-leading '-' or '_')")

  override val settings: Settings = new Settings(_cl, _config, name)

  override def logConfiguration(): Unit = log.info(settings.toString)

  protected def uncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() {
      def uncaughtException(thread: Thread, cause: Throwable): Unit = {
        cause match {
          case NonFatal(_) | _: InterruptedException | _: NotImplementedError | _: ControlThrowable ⇒ log.error(cause, "Uncaught error from thread [{}]", thread.getName)
          case _ ⇒
            if (settings.untyped.JvmExitOnFatalError) {
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
              terminate()
            }
        }
      }
    }

  override val threadFactory: d.MonitorableThreadFactory =
    d.MonitorableThreadFactory(name, settings.untyped.Daemonicity, Option(_cl), uncaughtExceptionHandler)

  override val dynamicAccess: a.DynamicAccess = new a.ReflectiveDynamicAccess(_cl)

  private val loggerIds = new AtomicInteger
  def loggerId(): Int = loggerIds.incrementAndGet()

  // this provides basic logging (to stdout) until .start() is called below
  override val eventStream = new EventStreamImpl(settings.untyped.DebugEventStream)(settings.untyped.LoggerStartTimeout)
  eventStream.startStdoutLogger(settings)

  override val logFilter: e.LoggingFilter = {
    val arguments = Vector(classOf[Settings] → settings, classOf[EventStream] → eventStream)
    dynamicAccess.createInstanceFor[e.LoggingFilter](settings.LoggingFilter, arguments).get
  }

  override val log: e.LoggingAdapter = new BusLogging(eventStream, getClass.getName + "(" + name + ")", this.getClass, logFilter)

  /**
   * Create the scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   *
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
  protected def createScheduler(): a.Scheduler =
    dynamicAccess.createInstanceFor[a.Scheduler](settings.untyped.SchedulerClass, immutable.Seq(
      classOf[Config] → settings.config,
      classOf[e.LoggingAdapter] → log,
      classOf[ThreadFactory] → threadFactory.withName(threadFactory.name + "-scheduler"))).get

  override val scheduler: a.Scheduler = createScheduler()
  private def closeScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  /**
   * Stub implementation of untyped EventStream to allow reuse of previous DispatcherConfigurator infrastructure
   */
  private object eventStreamStub extends e.EventStream(null, false) {
    override def subscribe(ref: a.ActorRef, ch: Class[_]): Boolean =
      throw new UnsupportedOperationException("cannot use this eventstream for subscribing")
    override def publish(event: AnyRef): Unit = eventStream.publish(event)
  }
  /**
   * Stub implementation of untyped Mailboxes to allow reuse of previous DispatcherConfigurator infrastructure
   */
  private val mailboxesStub = new d.Mailboxes(settings.untyped, eventStreamStub, dynamicAccess,
    new a.MinimalActorRef {
      override def path = rootPath
      override def provider = throw new UnsupportedOperationException("Mailboxes’ deadletter reference does not provide")
    })

  private val dispatcherPrequisites =
    d.DefaultDispatcherPrerequisites(threadFactory, eventStreamStub, scheduler, dynamicAccess, settings.untyped, mailboxesStub, _ec)
  override val dispatchers: Dispatchers = new DispatchersImpl(settings, log, dispatcherPrequisites)
  override val executionContext: ExecutionContextExecutor = dispatchers.lookup(DispatcherDefault())

  override val startTime: Long = System.currentTimeMillis()
  override def uptime: Long = (System.currentTimeMillis() - startTime) / 1000

  private val terminationPromise: Promise[Terminated] = Promise()

  private val rootPath: a.ActorPath = a.RootActorPath(a.Address("typed", name))

  private val topLevelActors = new ConcurrentSkipListSet[ActorRefImpl[Nothing]]
  private val terminateTriggered = new AtomicBoolean
  private val theOneWhoWalksTheBubblesOfSpaceTime: ActorRefImpl[Nothing] =
    new ActorRef[Nothing](rootPath) with ActorRefImpl[Nothing] {
      override def tell(msg: Nothing): Unit = throw new UnsupportedOperationException("cannot send to theOneWhoWalksTheBubblesOfSpaceTime")
      override def sendSystem(signal: SystemMessage): Unit = signal match {
        case Terminate() ⇒
          if (terminateTriggered.compareAndSet(false, true))
            topLevelActors.asScala.foreach(ref ⇒ ref.sendSystem(Terminate()))
        case DeathWatchNotification(ref, _) ⇒
          topLevelActors.remove(ref)
          if (topLevelActors.isEmpty) {
            if (terminationPromise.tryComplete(Success(Terminated(this)(null)))) {
              eventStream.stopDefaultLoggers(ActorSystemImpl.this)
              closeScheduler()
              dispatchers.shutdown()
            }
          } else if (terminateTriggered.compareAndSet(false, true))
            topLevelActors.asScala.foreach(ref ⇒ ref.sendSystem(Terminate()))
        case _ ⇒ // ignore
      }
      override def isLocal: Boolean = true
    }

  private def createTopLevel[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig): ActorRefImpl[U] = {
    val dispatcher = deployment.firstOrElse[DispatcherSelector](DispatcherFromExecutionContext(executionContext))
    val capacity = deployment.firstOrElse(MailboxCapacity(settings.DefaultMailboxCapacity))
    val cell = new ActorCell(this, behavior, dispatchers.lookup(dispatcher), capacity.capacity, theOneWhoWalksTheBubblesOfSpaceTime)
    val ref = new LocalActorRef(rootPath / name, cell)
    cell.setSelf(ref)
    topLevelActors.add(ref)
    ref.sendSystem(Create())
    ref
  }

  private val systemGuardian: ActorRefImpl[SystemCommand] = createTopLevel(systemGuardianBehavior, "system", EmptyDeploymentConfig)
  private val userGuardian: ActorRefImpl[T] = createTopLevel(_userGuardianBehavior, "user", _userGuardianDeployment)

  // now we can start up the loggers
  eventStream.startUnsubscriber(this)
  eventStream.startDefaultLoggers(this)

  override def terminate(): Future[Terminated] = {
    theOneWhoWalksTheBubblesOfSpaceTime.sendSystem(Terminate())
    terminationPromise.future
  }
  override def whenTerminated: Future[Terminated] = terminationPromise.future

  override def deadLetters[U]: ActorRefImpl[U] =
    new ActorRef[U](rootPath) with ActorRefImpl[U] {
      override def tell(msg: U): Unit = eventStream.publish(DeadLetter(msg))
      override def sendSystem(signal: SystemMessage): Unit = {
        signal match {
          case Watch(watchee, watcher) ⇒ watcher.sorryForNothing.sendSystem(DeathWatchNotification(watchee, null))
          case _                       ⇒ // all good
        }
        eventStream.publish(DeadLetter(signal))
      }
      override def isLocal: Boolean = true
    }

  override def tell(msg: T): Unit = userGuardian.tell(msg)
  override def sendSystem(msg: SystemMessage): Unit = userGuardian.sendSystem(msg)
  override def isLocal: Boolean = true

  def systemActorOf[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig)(implicit timeout: Timeout): Future[ActorRef[U]] = {
    import AskPattern._
    implicit val sched = scheduler
    systemGuardian ? CreateSystemActor(behavior, name, deployment)
  }

  def printTree: String = {
    def printNode(node: ActorRefImpl[Nothing], indent: String): String = {
      node match {
        case wc: LocalActorRef[_] ⇒
          val cell = wc.getCell
          (if (indent.isEmpty) "-> " else indent.dropRight(1) + "⌊-> ") +
            node.path.name + " " + e.Logging.simpleName(node) + " " +
            (if (cell.behavior ne null) cell.behavior.getClass else "null") +
            " status=" + cell.getStatus +
            " nextMsg=" + cell.peekMessage +
            (if (cell.children.isEmpty && cell.terminating.isEmpty) "" else "\n") +
            ({
              val terminating = cell.terminating.toSeq.sorted.map(r ⇒ printNode(r.sorryForNothing, indent + "   T"))
              val children = cell.children.toSeq.sorted
              val bulk = children.dropRight(1) map (r ⇒ printNode(r.sorryForNothing, indent + "   |"))
              terminating ++ bulk ++ (children.lastOption map (r ⇒ printNode(r.sorryForNothing, indent + "    ")))
            } mkString ("\n"))
        case _ ⇒
          indent + node.path.name + " " + e.Logging.simpleName(node)
      }
    }
    printNode(systemGuardian, "") + "\n" +
      printNode(userGuardian, "")
  }

}
