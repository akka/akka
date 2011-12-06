/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent._
import akka.event.Logging.Error
import akka.util.{ Duration, Switch, ReentrantGuard }
import atomic.{ AtomicInteger, AtomicLong }
import java.util.concurrent.ThreadPoolExecutor.{ AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy }
import akka.actor._
import akka.actor.ActorSystem
import locks.ReentrantLock
import scala.annotation.tailrec
import akka.event.EventStream
import akka.actor.ActorSystem.Settings
import com.typesafe.config.Config

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class Envelope(val message: Any, val sender: ActorRef) {
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
case class ChildTerminated(child: ActorRef) extends SystemMessage // sent to supervisor from ActorCell.doTerminate
case class Link(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.startsWatching
case class Unlink(subject: ActorRef) extends SystemMessage // sent to self from ActorCell.stopsWatching

final case class TaskInvocation(eventStream: EventStream, function: () ⇒ Unit, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      function()
    } catch {
      // FIXME catching all and continue isn't good for OOME, ticket #1418
      case e ⇒ eventStream.publish(Error(e, "TaskInvocation", e.getMessage))
    } finally {
      cleanup()
    }
  }
}

object MessageDispatcher {
  val UNSCHEDULED = 0 //WARNING DO NOT CHANGE THE VALUE OF THIS: It relies on the faster init of 0 in AbstractMessageDispatcher
  val SCHEDULED = 1
  val RESCHEDULED = 2

  implicit def defaultDispatcher(implicit system: ActorSystem) = system.dispatcher
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class MessageDispatcher(val prerequisites: DispatcherPrerequisites) extends AbstractMessageDispatcher with Serializable {

  import MessageDispatcher._
  import AbstractMessageDispatcher.{ inhabitantsUpdater, shutdownScheduleUpdater }
  import prerequisites._

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[akka] def createMailbox(actor: ActorCell): Mailbox

  /**
   * Name of this dispatcher.
   */
  def name: String

  /**
   * Attaches the specified actor instance to this dispatcher
   */
  final def attach(actor: ActorCell): Unit = register(actor)

  /**
   * Detaches the specified actor instance from this dispatcher
   */
  final def detach(actor: ActorCell): Unit = try {
    unregister(actor)
  } finally {
    ifSensibleToDoSoThenScheduleShutdown()
  }

  protected[akka] final def dispatchTask(block: () ⇒ Unit) {
    val invocation = TaskInvocation(eventStream, block, taskCleanup)
    inhabitantsUpdater.incrementAndGet(this)
    try {
      executeTask(invocation)
    } catch {
      case e ⇒
        inhabitantsUpdater.decrementAndGet(this)
        throw e
    }
  }

  @tailrec
  private final def ifSensibleToDoSoThenScheduleShutdown(): Unit = inhabitantsUpdater.get(this) match {
    case 0 ⇒
      shutdownScheduleUpdater.get(this) match {
        case UNSCHEDULED ⇒
          if (shutdownScheduleUpdater.compareAndSet(this, UNSCHEDULED, SCHEDULED)) {
            scheduler.scheduleOnce(shutdownTimeout, shutdownAction)
            ()
          } else ifSensibleToDoSoThenScheduleShutdown()
        case SCHEDULED ⇒
          if (shutdownScheduleUpdater.compareAndSet(this, SCHEDULED, RESCHEDULED)) ()
          else ifSensibleToDoSoThenScheduleShutdown()
        case RESCHEDULED ⇒ ()
      }
    case _ ⇒ ()
  }

  private final val taskCleanup: () ⇒ Unit =
    () ⇒ if (inhabitantsUpdater.decrementAndGet(this) == 0) ifSensibleToDoSoThenScheduleShutdown()

  /**
   * If you override it, you must call it. But only ever once. See "attach" for only invocation
   */
  protected[akka] def register(actor: ActorCell) {
    inhabitantsUpdater.incrementAndGet(this)
    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    systemDispatch(actor, Create()) //FIXME should this be here or moved into ActorCell.start perhaps?
  }

  /**
   * If you override it, you must call it. But only ever once. See "detach" for the only invocation
   */
  protected[akka] def unregister(actor: ActorCell) {
    inhabitantsUpdater.decrementAndGet(this)
    val mailBox = actor.mailbox
    mailBox.becomeClosed() // FIXME reschedule in tell if possible race with cleanUp is detected in order to properly clean up
    actor.mailbox = deadLetterMailbox
    cleanUpMailboxFor(actor, mailBox)
    mailBox.cleanUp()
  }

  /**
   * Overridable callback to clean up the mailbox for a given actor,
   * called when an actor is unregistered.
   */
  protected def cleanUpMailboxFor(actor: ActorCell, mailBox: Mailbox) {

    if (mailBox.hasSystemMessages) {
      var message = mailBox.systemDrain()
      while (message ne null) {
        // message must be “virgin” before being able to systemEnqueue again
        val next = message.next
        message.next = null
        deadLetterMailbox.systemEnqueue(actor.self, message)
        message = next
      }
    }

    if (mailBox.hasMessages) {
      var envelope = mailBox.dequeue
      while (envelope ne null) {
        deadLetterMailbox.enqueue(actor.self, envelope)
        envelope = mailBox.dequeue
      }
    }
  }

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
            try scheduler.scheduleOnce(shutdownTimeout, this) catch {
              case _: IllegalStateException ⇒ shutdown()
            }
          else run()
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down,
   * defaulting to your akka configs "akka.actor.dispatcher-shutdown-timeout" or default specified in
   * akka-actor-reference.conf
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
  @inline
  protected[akka] final val isThroughputDefined = throughput > 1

  protected[akka] def executeTask(invocation: TaskInvocation)

  /**
   * Called one time every time an actor is detached from this dispatcher and this dispatcher has no actors left attached
   * Must be idempotent
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
}

/**
 * Trait to be used for hooking in new dispatchers into Dispatchers.fromConfig
 */
abstract class MessageDispatcherConfigurator() {
  /**
   * Returns an instance of MessageDispatcher given a Configuration
   */
  def configure(config: Config, settings: Settings, prerequisites: DispatcherPrerequisites): MessageDispatcher

  def mailboxType(config: Config, settings: Settings): MailboxType = {
    val capacity = config.getInt("mailbox-capacity")
    if (capacity < 1) UnboundedMailbox()
    else {
      val duration = Duration(config.getNanoseconds("mailbox-push-timeout-time"), TimeUnit.NANOSECONDS)
      BoundedMailbox(capacity, duration)
    }
  }

  def configureThreadPool(config: Config,
                          settings: Settings,
                          createDispatcher: ⇒ (ThreadPoolConfig) ⇒ MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
    import ThreadPoolConfigDispatcherBuilder.conf_?
    import scala.math.{ min, max }

    //Apply the following options to the config if they are present in the config

    ThreadPoolConfigDispatcherBuilder(createDispatcher, ThreadPoolConfig())
      .setKeepAliveTime(Duration(config getMilliseconds "keep-alive-time", TimeUnit.MILLISECONDS))
      .setAllowCoreThreadTimeout(config getBoolean "allow-core-timeout")
      .setCorePoolSize(min(max(ThreadPoolConfig.scaledPoolSize(config getDouble "core-pool-size-factor"),
        config getInt "core-pool-size-min"), config getInt "core-pool-size-max"))
      .setMaxPoolSize(min(max(ThreadPoolConfig.scaledPoolSize(config getDouble "max-pool-size-factor"),
        config getInt "max-pool-size-min"), config getInt "max-pool-size-max"))
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
}
