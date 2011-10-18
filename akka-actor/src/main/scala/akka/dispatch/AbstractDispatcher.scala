/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import akka.event.EventHandler
import akka.config.Configuration
import akka.util.{ Duration, Switch, ReentrantGuard }
import java.util.concurrent.ThreadPoolExecutor.{ AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy }
import akka.actor._
import akka.AkkaApplication

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class Envelope(val receiver: ActorCell, val message: Any, val channel: UntypedChannel) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  final def invoke() {
    receiver invoke this
  }
}

sealed trait SystemMessage extends PossiblyHarmful {
  def next: SystemMessage
}
case class Create(next: SystemMessage = null) extends SystemMessage
case class Recreate(cause: Throwable, next: SystemMessage = null) extends SystemMessage
case class Suspend(next: SystemMessage = null) extends SystemMessage
case class Resume(next: SystemMessage = null) extends SystemMessage
case class Terminate(next: SystemMessage = null) extends SystemMessage
case class Supervise(child: ActorRef, next: SystemMessage = null) extends SystemMessage
case class Link(subject: ActorRef, next: SystemMessage = null) extends SystemMessage
case class Unlink(subject: ActorRef, next: SystemMessage = null) extends SystemMessage

final case class TaskInvocation(app: AkkaApplication, function: () ⇒ Unit, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      function()
    } catch {
      case e ⇒ app.eventHandler.error(e, this, e.getMessage)
    } finally {
      cleanup()
    }
  }
}

object MessageDispatcher {
  val UNSCHEDULED = 0
  val SCHEDULED = 1
  val RESCHEDULED = 2

  implicit def defaultDispatcher(implicit app: AkkaApplication) = app.dispatcher
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class MessageDispatcher(val app: AkkaApplication) extends Serializable {
  import MessageDispatcher._

  protected val uuids = new ConcurrentSkipListSet[Uuid]
  protected val _tasks = new AtomicLong(0L)
  protected val guard = new ReentrantGuard
  protected val active = new Switch(false)

  private var shutdownSchedule = UNSCHEDULED //This can be non-volatile since it is protected by guard withGuard

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[akka] def createMailbox(actor: ActorCell): Mailbox

  /**
   * Create a blackhole mailbox for the purpose of replacing the real one upon actor termination
   */
  protected[akka] val deadLetterMailbox: Mailbox = DeadLetterMailbox

  object DeadLetterMailbox extends Mailbox(null) {
    becomeClosed()
    override def dispatcher = null //MessageDispatcher.this
    override def enqueue(envelope: Envelope) { envelope.channel sendException new ActorKilledException("Actor has been stopped") }
    override def dequeue() = null
    override def systemEnqueue(handle: SystemMessage): Unit = ()
    override def systemDequeue(): SystemMessage = null
    override def hasMessages = false
    override def hasSystemMessages = false
    override def numberOfMessages = 0
  }

  /**
   * Name of this dispatcher.
   */
  def name: String

  /**
   * Attaches the specified actor instance to this dispatcher
   */
  final def attach(actor: ActorCell) {
    guard.lock.lock()
    try {
      startIfUnstarted()
      register(actor)
    } finally {
      guard.lock.unlock()
    }
  }

  /**
   * Detaches the specified actor instance from this dispatcher
   */
  final def detach(actor: ActorCell) {
    guard withGuard {
      unregister(actor)
      if (uuids.isEmpty && _tasks.get == 0) {
        shutdownSchedule match {
          case UNSCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            app.scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED ⇒
            shutdownSchedule = RESCHEDULED
          case RESCHEDULED ⇒ //Already marked for reschedule
        }
      }
    }
  }

  protected final def startIfUnstarted() {
    if (active.isOff) guard withGuard { active.switchOn { start() } }
  }

  protected[akka] final def dispatchTask(block: () ⇒ Unit) {
    _tasks.getAndIncrement()
    try {
      startIfUnstarted()
      executeTask(TaskInvocation(app, block, taskCleanup))
    } catch {
      case e ⇒
        _tasks.decrementAndGet
        throw e
    }
  }

  private val taskCleanup: () ⇒ Unit =
    () ⇒ if (_tasks.decrementAndGet() == 0) {
      guard withGuard {
        if (_tasks.get == 0 && uuids.isEmpty) {
          shutdownSchedule match {
            case UNSCHEDULED ⇒
              shutdownSchedule = SCHEDULED
              app.scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
            case SCHEDULED ⇒
              shutdownSchedule = RESCHEDULED
            case RESCHEDULED ⇒ //Already marked for reschedule
          }
        }
      }
    }

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "attach" for the only invocation
   */
  protected[akka] def register(actor: ActorCell) {
    if (uuids add actor.uuid) {
      systemDispatch(actor, Create()) //FIXME should this be here or moved into ActorCell.start perhaps?
    } else System.err.println("Couldn't register: " + actor)
  }

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "detach" for the only invocation
   */
  protected[akka] def unregister(actor: ActorCell) {
    if (uuids remove actor.uuid) {
      val mailBox = actor.mailbox
      mailBox.becomeClosed()
      actor.mailbox = deadLetterMailbox //FIXME getAndSet would be preferrable here
      cleanUpMailboxFor(actor, mailBox)
    } else System.err.println("Couldn't unregister: " + actor)
  }

  /**
   * Overridable callback to clean up the mailbox for a given actor,
   * called when an actor is unregistered.
   */
  protected def cleanUpMailboxFor(actor: ActorCell, mailBox: Mailbox) {

    if (mailBox.hasSystemMessages) {
      var envelope = mailBox.systemDequeue()
      while (envelope ne null) {
        deadLetterMailbox.systemEnqueue(envelope)
        envelope = mailBox.systemDequeue()
      }
    }

    if (mailBox.hasMessages) {
      var envelope = mailBox.dequeue
      while (envelope ne null) {
        deadLetterMailbox.enqueue(envelope)
        envelope = mailBox.dequeue
      }
    }
  }

  private val shutdownAction = new Runnable {
    def run() {
      guard withGuard {
        shutdownSchedule match {
          case RESCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            app.scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED ⇒
            if (uuids.isEmpty && _tasks.get == 0) {
              active switchOff {
                shutdown() // shut down in the dispatcher's references is zero
              }
            }
            shutdownSchedule = UNSCHEDULED
          case UNSCHEDULED ⇒ //Do nothing
        }
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down, in Ms
   * defaulting to your akka configs "akka.actor.dispatcher-shutdown-timeout" or otherwise, 1 Second
   */
  protected[akka] def timeoutMs: Long

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actor: ActorCell): Unit =
    if (uuids.contains(actor.uuid)) actor.mailbox.becomeSuspended()

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actor: ActorCell): Unit = if (uuids.contains(actor.uuid)) {
    val mbox = actor.mailbox
    mbox.becomeOpen()
    registerForExecution(mbox, false, false)
  }

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def systemDispatch(receiver: ActorCell, invocation: SystemMessage)

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def dispatch(invocation: Envelope)

  /**
   * Suggest to register the provided mailbox for execution
   */
  protected[akka] def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean

  // TODO check whether this should not actually be a property of the mailbox
  protected[akka] def throughput: Int
  protected[akka] def throughputDeadlineTime: Int

  @inline
  protected[akka] final val isThroughputDeadlineTimeDefined = throughputDeadlineTime > 0
  @inline
  protected[akka] final val isThroughputDefined = throughput > 1

  protected[akka] def executeTask(invocation: TaskInvocation)

  /**
   * Called one time every time an actor is attached to this dispatcher and this dispatcher was previously shutdown
   */
  protected[akka] def start(): Unit

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   */
  protected[akka] def shutdown(): Unit

  /**
   * Returns the size of the mailbox for the specified actor
   */
  def mailboxSize(actor: ActorCell): Int = actor.mailbox.numberOfMessages

  /**
   * Returns the "current" emptiness status of the mailbox for the specified actor
   */
  def mailboxIsEmpty(actor: ActorCell): Boolean = !actor.mailbox.hasMessages

  /**
   * Returns the amount of tasks queued for execution
   */
  def tasks: Long = _tasks.get
}

/**
 * Trait to be used for hooking in new dispatchers into Dispatchers.fromConfig
 */
abstract class MessageDispatcherConfigurator(val app: AkkaApplication) {
  /**
   * Returns an instance of MessageDispatcher given a Configuration
   */
  def configure(config: Configuration): MessageDispatcher

  def mailboxType(config: Configuration): MailboxType = {
    val capacity = config.getInt("mailbox-capacity", app.AkkaConfig.MailboxCapacity)
    if (capacity < 1) UnboundedMailbox()
    else {
      val duration = Duration(
        config.getInt("mailbox-push-timeout-time", app.AkkaConfig.MailboxPushTimeout.toMillis.toInt),
        app.AkkaConfig.DefaultTimeUnit)
      BoundedMailbox(capacity, duration)
    }
  }

  def configureThreadPool(config: Configuration, createDispatcher: ⇒ (ThreadPoolConfig) ⇒ MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
    import ThreadPoolConfigDispatcherBuilder.conf_?

    //Apply the following options to the config if they are present in the config
    ThreadPoolConfigDispatcherBuilder(createDispatcher, ThreadPoolConfig(app)).configure(
      conf_?(config getInt "keep-alive-time")(time ⇒ _.setKeepAliveTime(Duration(time, app.AkkaConfig.DefaultTimeUnit))),
      conf_?(config getDouble "core-pool-size-factor")(factor ⇒ _.setCorePoolSizeFromFactor(factor)),
      conf_?(config getDouble "max-pool-size-factor")(factor ⇒ _.setMaxPoolSizeFromFactor(factor)),
      conf_?(config getInt "executor-bounds")(bounds ⇒ _.setExecutorBounds(bounds)),
      conf_?(config getBool "allow-core-timeout")(allow ⇒ _.setAllowCoreThreadTimeout(allow)),
      conf_?(config getInt "task-queue-size" flatMap {
        case size if size > 0 ⇒
          config getString "task-queue-type" map {
            case "array"       ⇒ ThreadPoolConfig.arrayBlockingQueue(size, false) //TODO config fairness?
            case "" | "linked" ⇒ ThreadPoolConfig.linkedBlockingQueue(size)
            case x             ⇒ throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!" format x)
          }
        case _ ⇒ None
      })(queueFactory ⇒ _.setQueueFactory(queueFactory)),
      conf_?(config getString "rejection-policy" map {
        case "abort"          ⇒ new AbortPolicy()
        case "caller-runs"    ⇒ new CallerRunsPolicy()
        case "discard-oldest" ⇒ new DiscardOldestPolicy()
        case "discard"        ⇒ new DiscardPolicy()
        case x                ⇒ throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy [abort|caller-runs|discard-oldest|discard]!" format x)
      })(policy ⇒ _.setRejectionPolicy(policy)))
  }
}
