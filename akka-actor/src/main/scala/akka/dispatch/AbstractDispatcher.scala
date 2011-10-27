/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import akka.event.Logging.Error
import akka.config.Configuration
import akka.util.{ Duration, Switch, ReentrantGuard }
import java.util.concurrent.ThreadPoolExecutor.{ AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy }
import akka.actor._
import akka.AkkaApplication
import scala.annotation.tailrec

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class Envelope(val message: Any, val channel: UntypedChannel) {
  if (message.isInstanceOf[AnyRef] && (message.asInstanceOf[AnyRef] eq null)) throw new InvalidMessageException("Message is null")
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
  var next: SystemMessage = _
}
case class Create() extends SystemMessage // send to self from Dispatcher.register
case class Recreate(cause: Throwable) extends SystemMessage // sent to self from ActorCell.restart
case class Suspend() extends SystemMessage // sent to self from ActorCell.suspend
case class Resume() extends SystemMessage // sent to self from ActorCell.resume
case class Terminate() extends SystemMessage // sent to self from ActorCell.stop
case class Supervise(child: ActorRef) extends SystemMessage // sent to supervisor ActorRef from ActorCell.start
case class Link(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.startsMonitoring
case class Unlink(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.stopsMonitoring

final case class TaskInvocation(app: AkkaApplication, function: () ⇒ Unit, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      function()
    } catch {
      case e ⇒ app.mainbus.publish(Error(e, this, e.getMessage))
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

  protected val _tasks = new AtomicLong(0L)
  protected val _actors = new AtomicLong(0L)
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
    override def systemDrain(): SystemMessage = null
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
    guard.lock.lock()
    try {
      unregister(actor)
      if (_tasks.get == 0 && _actors.get == 0) {
        shutdownSchedule match {
          case UNSCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            app.scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED ⇒
            shutdownSchedule = RESCHEDULED
          case RESCHEDULED ⇒ //Already marked for reschedule
        }
      }
    } finally { guard.lock.unlock() }
  }

  protected final def startIfUnstarted() {
    if (active.isOff) {
      guard.lock.lock()
      try { active.switchOn { start() } }
      finally { guard.lock.unlock() }
    }
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
      guard.lock.lock()
      try {
        if (_tasks.get == 0 && _actors.get == 0) {
          shutdownSchedule match {
            case UNSCHEDULED ⇒
              shutdownSchedule = SCHEDULED
              app.scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
            case SCHEDULED ⇒
              shutdownSchedule = RESCHEDULED
            case RESCHEDULED ⇒ //Already marked for reschedule
          }
        }
      } finally { guard.lock.unlock() }
    }

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "attach" for the only invocation
   */
  protected[akka] def register(actor: ActorCell) {
    _actors.incrementAndGet()
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    systemDispatch(actor, Create()) //FIXME should this be here or moved into ActorCell.start perhaps?
  }

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "detach" for the only invocation
   */
  protected[akka] def unregister(actor: ActorCell) {
    _actors.decrementAndGet()
    val mailBox = actor.mailbox
    mailBox.becomeClosed()
    actor.mailbox = deadLetterMailbox //FIXME getAndSet would be preferrable here
    cleanUpMailboxFor(actor, mailBox)
  }

  /**
   * Overridable callback to clean up the mailbox for a given actor,
   * called when an actor is unregistered.
   */
  protected def cleanUpMailboxFor(actor: ActorCell, mailBox: Mailbox) {

    if (mailBox.hasSystemMessages) {
      var message = mailBox.systemDrain()
      while (message ne null) {
        deadLetterMailbox.systemEnqueue(message)
        message = message.next
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
      guard.lock.lock()
      try {
        shutdownSchedule match {
          case RESCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            app.scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED ⇒
            if (_tasks.get == 0) {
              active switchOff {
                shutdown() // shut down in the dispatcher's references is zero
              }
            }
            shutdownSchedule = UNSCHEDULED
          case UNSCHEDULED ⇒ //Do nothing
        }
      } finally { guard.lock.unlock() }
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
    if (mbox.dispatcher eq this) {
      mbox.becomeOpen()
      registerForExecution(mbox, false, false)
    }
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
