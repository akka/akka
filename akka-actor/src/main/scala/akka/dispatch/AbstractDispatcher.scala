/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent._
import akka.event.Logging.Error
import akka.util.Duration
import akka.actor._
import akka.actor.ActorSystem
import scala.annotation.tailrec
import akka.event.EventStream
import com.typesafe.config.Config
import akka.serialization.SerializationExtension
import akka.util.NonFatal
import akka.event.Logging.LogEventException
import akka.jsr166y.{ ForkJoinTask, ForkJoinPool }

final case class Envelope(val message: Any, val sender: ActorRef)(system: ActorSystem) {
  if (message.isInstanceOf[AnyRef]) {
    val msg = message.asInstanceOf[AnyRef]
    if (msg eq null) throw new InvalidMessageException("Message is null")
    if (system.settings.SerializeAllMessages && !msg.isInstanceOf[NoSerializationVerificationNeeded]) {
      val ser = SerializationExtension(system)
      ser.serialize(msg) match { //Verify serializability
        case Left(t) ⇒ throw t
        case Right(bytes) ⇒ ser.deserialize(bytes, msg.getClass) match { //Verify deserializability
          case Left(t) ⇒ throw t
          case _       ⇒ //All good
        }
      }
    }
  }
}

object SystemMessage {
  @tailrec
  final def size(list: SystemMessage, acc: Int = 0): Int = {
    if (list eq null) acc else size(list.next, acc + 1)
  }

  @tailrec
  final def reverse(list: SystemMessage, acc: SystemMessage = null): SystemMessage = {
    if (list eq null) acc else {
      val next = list.next
      list.next = acc
      reverse(next, list)
    }
  }
}

/**
 * System messages are handled specially: they form their own queue within
 * each actor’s mailbox. This queue is encoded in the messages themselves to
 * avoid extra allocations and overhead. The next pointer is a normal var, and
 * it does not need to be volatile because in the enqueuing method its update
 * is immediately succeeded by a volatile write and all reads happen after the
 * volatile read in the dequeuing thread. Afterwards, the obtained list of
 * system messages is handled in a single thread only and not ever passed around,
 * hence no further synchronization is needed.
 *
 * ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
 */
sealed trait SystemMessage extends PossiblyHarmful {
  @transient
  var next: SystemMessage = _
}
case class Create() extends SystemMessage // send to self from Dispatcher.register
case class Recreate(cause: Throwable) extends SystemMessage // sent to self from ActorCell.restart
case class Suspend() extends SystemMessage // sent to self from ActorCell.suspend
case class Resume() extends SystemMessage // sent to self from ActorCell.resume
case class Terminate() extends SystemMessage // sent to self from ActorCell.stop
case class Supervise(child: ActorRef) extends SystemMessage // sent to supervisor ActorRef from ActorCell.start
case class ChildTerminated(child: ActorRef) extends SystemMessage // sent to supervisor from ActorCell.doTerminate
case class Link(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.watch
case class Unlink(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.unwatch

final case class TaskInvocation(eventStream: EventStream, runnable: Runnable, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      runnable.run()
    } catch {
      case NonFatal(e) ⇒
        eventStream.publish(Error(e, "TaskInvocation", this.getClass, e.getMessage))
    } finally {
      cleanup()
    }
  }
}

/**
 * Java API to create ExecutionContexts
 */
object ExecutionContexts {

  /**
   * Creates an ExecutionContext from the given ExecutorService
   */
  def fromExecutorService(e: ExecutorService): ExecutionContextExecutorService =
    new ExecutionContext.WrappedExecutorService(e)

  /**
   * Creates an ExecutionContext from the given Executor
   */
  def fromExecutor(e: Executor): ExecutionContextExecutor =
    new ExecutionContext.WrappedExecutor(e)
}

object ExecutionContext {
  implicit def defaultExecutionContext(implicit system: ActorSystem): ExecutionContext = system.dispatcher

  /**
   * Creates an ExecutionContext from the given ExecutorService
   */
  def fromExecutorService(e: ExecutorService): ExecutionContext with ExecutorService = new WrappedExecutorService(e)

  /**
   * Creates an ExecutionContext from the given Executor
   */
  def fromExecutor(e: Executor): ExecutionContext with Executor = new WrappedExecutor(e)

  /**
   * Internal Akka use only
   */
  private[akka] class WrappedExecutorService(val executor: ExecutorService) extends ExecutorServiceDelegate with ExecutionContextExecutorService {
    override def reportFailure(t: Throwable): Unit = t match {
      case e: LogEventException ⇒ e.getCause.printStackTrace()
      case _                    ⇒ t.printStackTrace()
    }
  }

  /**
   * Internal Akka use only
   */
  private[akka] class WrappedExecutor(val executor: Executor) extends ExecutionContextExecutor {
    override final def execute(runnable: Runnable): Unit = executor.execute(runnable)
    override def reportFailure(t: Throwable): Unit = t match {
      case e: LogEventException ⇒ e.getCause.printStackTrace()
      case _                    ⇒ t.printStackTrace()
    }
  }
}

/**
 * Union interface since Java does not support union types
 */
trait ExecutionContextExecutor extends ExecutionContext with Executor

/**
 * Union interface since Java does not support union types
 */
trait ExecutionContextExecutorService extends ExecutionContextExecutor with ExecutorService

/**
 * An ExecutionContext is essentially the same thing as a java.util.concurrent.Executor
 * This interface/trait exists to decouple the concept of execution from Actors & MessageDispatchers
 * It is also needed to provide a fallback implicit default instance (in the companion object).
 */
trait ExecutionContext {

  /**
   * Submits the runnable for execution
   */
  def execute(runnable: Runnable): Unit

  /**
   * Failed tasks should call reportFailure to let the ExecutionContext
   * log the problem or whatever is appropriate for the implementation.
   */
  def reportFailure(t: Throwable): Unit
}

private[akka] trait LoadMetrics { self: Executor ⇒
  def atFullThrottle(): Boolean
}

object MessageDispatcher {
  val UNSCHEDULED = 0 //WARNING DO NOT CHANGE THE VALUE OF THIS: It relies on the faster init of 0 in AbstractMessageDispatcher
  val SCHEDULED = 1
  val RESCHEDULED = 2

  implicit def defaultDispatcher(implicit system: ActorSystem): MessageDispatcher = system.dispatcher
}

abstract class MessageDispatcher(val prerequisites: DispatcherPrerequisites) extends AbstractMessageDispatcher with Executor with ExecutionContext {

  import MessageDispatcher._
  import AbstractMessageDispatcher.{ inhabitantsUpdater, shutdownScheduleUpdater }
  import prerequisites._

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[akka] def createMailbox(actor: ActorCell): Mailbox

  /**
   * Identifier of this dispatcher, corresponds to the full key
   * of the dispatcher configuration.
   */
  def id: String

  /**
   * Attaches the specified actor instance to this dispatcher, which includes
   * scheduling it to run for the first time (Create() is expected to have
   * been enqueued by the ActorCell upon mailbox creation).
   */
  final def attach(actor: ActorCell): Unit = {
    register(actor)
    registerForExecution(actor.mailbox, false, true)
  }

  /**
   * Detaches the specified actor instance from this dispatcher
   */
  final def detach(actor: ActorCell): Unit = try {
    unregister(actor)
  } finally {
    ifSensibleToDoSoThenScheduleShutdown()
  }

  final def execute(runnable: Runnable) {
    val invocation = TaskInvocation(eventStream, runnable, taskCleanup)
    inhabitantsUpdater.incrementAndGet(this)
    try {
      executeTask(invocation)
    } catch {
      case t ⇒
        inhabitantsUpdater.decrementAndGet(this)
        throw t
    }
  }

  def reportFailure(t: Throwable): Unit = t match {
    case e: LogEventException ⇒ prerequisites.eventStream.publish(e.event)
    case _                    ⇒ prerequisites.eventStream.publish(Error(t, getClass.getName, getClass, t.getMessage))
  }

  @tailrec
  private final def ifSensibleToDoSoThenScheduleShutdown(): Unit = inhabitantsUpdater.get(this) match {
    case 0 ⇒
      shutdownScheduleUpdater.get(this) match {
        case UNSCHEDULED ⇒
          if (shutdownScheduleUpdater.compareAndSet(this, UNSCHEDULED, SCHEDULED)) {
            scheduleShutdownAction()
            ()
          } else ifSensibleToDoSoThenScheduleShutdown()
        case SCHEDULED ⇒
          if (shutdownScheduleUpdater.compareAndSet(this, SCHEDULED, RESCHEDULED)) ()
          else ifSensibleToDoSoThenScheduleShutdown()
        case RESCHEDULED ⇒ ()
      }
    case _ ⇒ ()
  }

  private def scheduleShutdownAction(): Unit = {
    // IllegalStateException is thrown if scheduler has been shutdown
    try scheduler.scheduleOnce(shutdownTimeout, shutdownAction) catch {
      case _: IllegalStateException ⇒ shutdown()
    }
  }

  private final val taskCleanup: () ⇒ Unit =
    () ⇒ if (inhabitantsUpdater.decrementAndGet(this) == 0) ifSensibleToDoSoThenScheduleShutdown()

  /**
   * If you override it, you must call it. But only ever once. See "attach" for only invocation.
   */
  protected[akka] def register(actor: ActorCell) {
    inhabitantsUpdater.incrementAndGet(this)
  }

  /**
   * If you override it, you must call it. But only ever once. See "detach" for the only invocation
   */
  protected[akka] def unregister(actor: ActorCell) {
    inhabitantsUpdater.decrementAndGet(this)
    val mailBox = actor.mailbox
    mailBox.becomeClosed() // FIXME reschedule in tell if possible race with cleanUp is detected in order to properly clean up
    actor.mailbox = deadLetterMailbox
    mailBox.cleanUp()
  }

  def inhabitants: Long = inhabitantsUpdater.get(this)

  private val shutdownAction = new Runnable {
    @tailrec
    final def run() {
      shutdownScheduleUpdater.get(MessageDispatcher.this) match {
        case UNSCHEDULED ⇒ ()
        case SCHEDULED ⇒
          try {
            if (inhabitantsUpdater.get(MessageDispatcher.this) == 0) //Warning, racy
              shutdown()
          } finally {
            shutdownScheduleUpdater.getAndSet(MessageDispatcher.this, UNSCHEDULED) //TODO perhaps check if it was modified since we checked?
          }
        case RESCHEDULED ⇒
          if (shutdownScheduleUpdater.compareAndSet(MessageDispatcher.this, RESCHEDULED, SCHEDULED))
            scheduleShutdownAction()
          else run()
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down,
   * defaulting to your akka configs "akka.actor.default-dispatcher.shutdown-timeout" or default specified in
   * reference.conf
   */
  protected[akka] def shutdownTimeout: Duration

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actor: ActorCell): Unit = {
    val mbox = actor.mailbox
    if (mbox.dispatcher eq this)
      mbox.becomeSuspended()
  }

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actor: ActorCell): Unit = {
    val mbox = actor.mailbox
    if ((mbox.dispatcher eq this) && mbox.becomeOpen())
      registerForExecution(mbox, false, false)
  }

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def systemDispatch(receiver: ActorCell, invocation: SystemMessage)

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope)

  /**
   * Suggest to register the provided mailbox for execution
   */
  protected[akka] def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean

  // TODO check whether this should not actually be a property of the mailbox
  protected[akka] def throughput: Int
  protected[akka] def throughputDeadlineTime: Duration

  @inline
  protected[akka] final val isThroughputDeadlineTimeDefined = throughputDeadlineTime.toMillis > 0

  protected[akka] def executeTask(invocation: TaskInvocation)

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   * Must be idempotent
   */
  protected[akka] def shutdown(): Unit
}

abstract class ExecutorServiceConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceFactoryProvider

/**
 * Base class to be used for hooking in new dispatchers into Dispatchers.
 */
abstract class MessageDispatcherConfigurator(val config: Config, val prerequisites: DispatcherPrerequisites) {

  /**
   * Returns an instance of MessageDispatcher given the configuration.
   * Depending on the needs the implementation may return a new instance for
   * each invocation or return the same instance every time.
   */
  def dispatcher(): MessageDispatcher

  /**
   * Returns a factory for the [[akka.dispatch.Mailbox]] given the configuration.
   * Default implementation instantiate the [[akka.dispatch.MailboxType]] specified
   * as FQCN in mailbox-type config property. If mailbox-type is unspecified (empty)
   * then [[akka.dispatch.UnboundedMailbox]] is used when capacity is < 1,
   * otherwise [[akka.dispatch.BoundedMailbox]].
   */
  def mailboxType(): MailboxType = {
    config.getString("mailbox-type") match {
      case "" ⇒
        if (config.getInt("mailbox-capacity") < 1) UnboundedMailbox()
        else new BoundedMailbox(prerequisites.settings, config)
      case "unbounded" ⇒ UnboundedMailbox()
      case "bounded"   ⇒ new BoundedMailbox(prerequisites.settings, config)
      case fqcn ⇒
        val args = Seq(classOf[ActorSystem.Settings] -> prerequisites.settings, classOf[Config] -> config)
        prerequisites.dynamicAccess.createInstanceFor[MailboxType](fqcn, args) match {
          case Right(instance) ⇒ instance
          case Left(exception) ⇒
            throw new IllegalArgumentException(
              ("Cannot instantiate MailboxType [%s], defined in [%s], " +
                "make sure it has constructor with [akka.actor.ActorSystem.Settings, com.typesafe.config.Config] parameters")
                .format(fqcn, config.getString("id")), exception)
        }
    }
  }

  def configureExecutor(): ExecutorServiceConfigurator = {
    config.getString("executor") match {
      case null | "" | "fork-join-executor" ⇒ new ForkJoinExecutorConfigurator(config.getConfig("fork-join-executor"), prerequisites)
      case "thread-pool-executor"           ⇒ new ThreadPoolExecutorConfigurator(config.getConfig("thread-pool-executor"), prerequisites)
      case fqcn ⇒
        val args = Seq(
          classOf[Config] -> config,
          classOf[DispatcherPrerequisites] -> prerequisites)
        prerequisites.dynamicAccess.createInstanceFor[ExecutorServiceConfigurator](fqcn, args) match {
          case Right(instance) ⇒ instance
          case Left(exception) ⇒ throw new IllegalArgumentException(
            ("""Cannot instantiate ExecutorServiceConfigurator ("executor = [%s]"), defined in [%s],
                make sure it has an accessible constructor with a [%s,%s] signature""")
              .format(fqcn, config.getString("id"), classOf[Config], classOf[DispatcherPrerequisites]), exception)
        }
    }
  }
}

class ThreadPoolExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {
  import ThreadPoolConfigBuilder.conf_?

  val threadPoolConfig: ThreadPoolConfig = createThreadPoolConfigBuilder(config, prerequisites).config

  protected def createThreadPoolConfigBuilder(config: Config, prerequisites: DispatcherPrerequisites): ThreadPoolConfigBuilder = {
    ThreadPoolConfigBuilder(ThreadPoolConfig())
      .setKeepAliveTime(Duration(config getMilliseconds "keep-alive-time", TimeUnit.MILLISECONDS))
      .setAllowCoreThreadTimeout(config getBoolean "allow-core-timeout")
      .setCorePoolSizeFromFactor(config getInt "core-pool-size-min", config getDouble "core-pool-size-factor", config getInt "core-pool-size-max")
      .setMaxPoolSizeFromFactor(config getInt "max-pool-size-min", config getDouble "max-pool-size-factor", config getInt "max-pool-size-max")
      .configure(
        conf_?(Some(config getInt "task-queue-size") flatMap {
          case size if size > 0 ⇒
            Some(config getString "task-queue-type") map {
              case "array"       ⇒ ThreadPoolConfig.arrayBlockingQueue(size, false) //TODO config fairness?
              case "" | "linked" ⇒ ThreadPoolConfig.linkedBlockingQueue(size)
              case x             ⇒ throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!" format x)
            }
          case _ ⇒ None
        })(queueFactory ⇒ _.setQueueFactory(queueFactory)))
  }

  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    val tf = threadFactory match {
      case m: MonitorableThreadFactory ⇒
        // add the dispatcher id to the thread names
        m.copy(m.name + "-" + id)
      case other ⇒ other
    }
    threadPoolConfig.createExecutorServiceFactory(id, tf)
  }
}

object ForkJoinExecutorConfigurator {

  /**
   * INTERNAL AKKA USAGE ONLY
   */
  final class AkkaForkJoinPool(parallelism: Int,
                               threadFactory: ForkJoinPool.ForkJoinWorkerThreadFactory,
                               unhandledExceptionHandler: Thread.UncaughtExceptionHandler)
    extends ForkJoinPool(parallelism, threadFactory, unhandledExceptionHandler, true) with LoadMetrics {
    override def execute(r: Runnable): Unit = r match {
      case m: Mailbox ⇒ super.execute(new MailboxExecutionTask(m))
      case other      ⇒ super.execute(other)
    }

    def atFullThrottle(): Boolean = this.getActiveThreadCount() >= this.getParallelism()
  }

  /**
   * INTERNAL AKKA USAGE ONLY
   */
  final class MailboxExecutionTask(mailbox: Mailbox) extends ForkJoinTask[Unit] {
    final override def setRawResult(u: Unit): Unit = ()
    final override def getRawResult(): Unit = ()
    final override def exec(): Boolean = try { mailbox.run; true } catch {
      case anything ⇒
        val t = Thread.currentThread
        t.getUncaughtExceptionHandler match {
          case null ⇒
          case some ⇒ some.uncaughtException(t, anything)
        }
        throw anything
    }
  }
}

class ForkJoinExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {
  import ForkJoinExecutorConfigurator._

  def validate(t: ThreadFactory): ForkJoinPool.ForkJoinWorkerThreadFactory = t match {
    case correct: ForkJoinPool.ForkJoinWorkerThreadFactory ⇒ correct
    case x ⇒ throw new IllegalStateException("The prerequisites for the ForkJoinExecutorConfigurator is a ForkJoinPool.ForkJoinWorkerThreadFactory!")
  }

  class ForkJoinExecutorServiceFactory(val threadFactory: ForkJoinPool.ForkJoinWorkerThreadFactory,
                                       val parallelism: Int) extends ExecutorServiceFactory {
    def createExecutorService: ExecutorService = new AkkaForkJoinPool(parallelism, threadFactory, MonitorableThreadFactory.doNothing)
  }
  final def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    val tf = threadFactory match {
      case m: MonitorableThreadFactory ⇒
        // add the dispatcher id to the thread names
        m.copy(m.name + "-" + id)
      case other ⇒ other
    }
    new ForkJoinExecutorServiceFactory(
      validate(tf),
      ThreadPoolConfig.scaledPoolSize(
        config.getInt("parallelism-min"),
        config.getDouble("parallelism-factor"),
        config.getInt("parallelism-max")))
  }
}
