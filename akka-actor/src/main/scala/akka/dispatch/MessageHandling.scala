/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicLong
import akka.event.EventHandler
import akka.config.Configuration
import akka.config.Config.TIME_UNIT
import akka.util.{ Duration, Switch, ReentrantGuard }
import java.util.concurrent.ThreadPoolExecutor.{ AbortPolicy, CallerRunsPolicy, DiscardOldestPolicy, DiscardPolicy }
import akka.actor._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
final case class MessageInvocation(val receiver: ActorRef,
                                   val message: Any,
                                   val channel: UntypedChannel) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  def invoke = try {
    receiver.invoke(this)
  } catch {
    case e: NullPointerException ⇒ throw new ActorInitializationException(
      "Don't call 'self ! message' in the Actor's constructor (in Scala this means in the body of the class).")
  }
}

final case class TaskInvocation(function: () ⇒ Unit, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      function()
    } catch {
      case e ⇒ EventHandler.error(e, this, e.getMessage)
    }
    finally {
      cleanup()
    }
  }
}

object MessageDispatcher {
  val UNSCHEDULED = 0
  val SCHEDULED = 1
  val RESCHEDULED = 2

  implicit def defaultGlobalDispatcher = Dispatchers.defaultGlobalDispatcher
}

/**
 *  @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait MessageDispatcher {
  import MessageDispatcher._

  protected val uuids = new ConcurrentSkipListSet[Uuid]
  protected val _tasks = new AtomicLong(0L)
  protected val guard = new ReentrantGuard
  protected val active = new Switch(false)

  private var shutdownSchedule = UNSCHEDULED //This can be non-volatile since it is protected by guard withGuard

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[akka] def createMailbox(actorRef: ActorRef): AnyRef

  /**
   * Name of this dispatcher.
   */
  def name: String

  /**
   * Attaches the specified actorRef to this dispatcher
   */
  final def attach(actorRef: ActorRef): Unit = guard withGuard {
    register(actorRef)
  }

  /**
   * Detaches the specified actorRef from this dispatcher
   */
  final def detach(actorRef: ActorRef): Unit = guard withGuard {
    unregister(actorRef)
  }

  protected[akka] final def dispatchMessage(invocation: MessageInvocation): Unit = dispatch(invocation)

  protected[akka] final def dispatchTask(block: () ⇒ Unit): Unit = {
    _tasks.getAndIncrement()
    try {
      if (active.isOff)
        guard withGuard {
          active.switchOn {
            start()
          }
        }
      executeTask(TaskInvocation(block, taskCleanup))
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
              Scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
            case SCHEDULED ⇒
              shutdownSchedule = RESCHEDULED
            case RESCHEDULED ⇒ //Already marked for reschedule
          }
        }
      }
    }

  protected[akka] def register(actorRef: ActorRef) {
    if (actorRef.mailbox eq null)
      actorRef.mailbox = createMailbox(actorRef)

    uuids add actorRef.uuid
    if (active.isOff) {
      active.switchOn {
        start
      }
    }
  }

  protected[akka] def unregister(actorRef: ActorRef) = {
    if (uuids remove actorRef.uuid) {
      actorRef.mailbox = null
      if (uuids.isEmpty && _tasks.get == 0) {
        shutdownSchedule match {
          case UNSCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            Scheduler.scheduleOnce(shutdownAction, timeoutMs, TimeUnit.MILLISECONDS)
          case SCHEDULED ⇒
            shutdownSchedule = RESCHEDULED
          case RESCHEDULED ⇒ //Already marked for reschedule
        }
      }
    }
  }

  /**
   * Traverses the list of actors (uuids) currently being attached to this dispatcher and stops those actors
   */
  def stopAllAttachedActors {
    val i = uuids.iterator
    while (i.hasNext()) {
      val uuid = i.next()
      Actor.registry.actorFor(uuid) match {
        case Some(actor) ⇒ actor.stop()
        case None        ⇒ {}
      }
    }
  }

  private val shutdownAction = new Runnable {
    def run = guard withGuard {
      shutdownSchedule match {
        case RESCHEDULED ⇒
          shutdownSchedule = SCHEDULED
          Scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
        case SCHEDULED ⇒
          if (uuids.isEmpty && _tasks.get == 0) {
            active switchOff {
              shutdown // shut down in the dispatcher's references is zero
            }
          }
          shutdownSchedule = UNSCHEDULED
        case UNSCHEDULED ⇒ //Do nothing
      }
    }
  }

  /**
   * When the dispatcher no longer has any actors registered, how long will it wait until it shuts itself down, in Ms
   * defaulting to your akka configs "akka.actor.dispatcher-shutdown-timeout" or otherwise, 1 Second
   */
  protected[akka] def timeoutMs: Long = Dispatchers.DEFAULT_SHUTDOWN_TIMEOUT.toMillis

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actorRef: ActorRef): Unit

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actorRef: ActorRef): Unit

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def dispatch(invocation: MessageInvocation): Unit

  protected[akka] def executeTask(invocation: TaskInvocation): Unit

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
  def mailboxSize(actorRef: ActorRef): Int

  /**
   * Returns the "current" emptiness status of the mailbox for the specified actor
   */
  def mailboxIsEmpty(actorRef: ActorRef): Boolean

  /**
   * Returns the amount of futures queued for execution
   */
  @deprecated("Will be removed for Akka 2.0, use 'tasks' instead", "2.0")
  def pendingFutures: Long = tasks

  /**
   * Returns the amount of tasks queued for execution
   */
  def tasks: Long = _tasks.get
}

/**
 * Trait to be used for hooking in new dispatchers into Dispatchers.fromConfig
 */
abstract class MessageDispatcherConfigurator {
  /**
   * Returns an instance of MessageDispatcher given a Configuration
   */
  def configure(config: Configuration): MessageDispatcher

  def mailboxType(config: Configuration): MailboxType = {
    val capacity = config.getInt("mailbox-capacity", Dispatchers.MAILBOX_CAPACITY)
    if (capacity < 1) UnboundedMailbox()
    else BoundedMailbox(capacity, Duration(config.getInt("mailbox-push-timeout-time", Dispatchers.MAILBOX_PUSH_TIME_OUT.toMillis.toInt), TIME_UNIT))
  }

  def configureThreadPool(config: Configuration, createDispatcher: ⇒ (ThreadPoolConfig) ⇒ MessageDispatcher): ThreadPoolConfigDispatcherBuilder = {
    import ThreadPoolConfigDispatcherBuilder.conf_?

    //Apply the following options to the config if they are present in the config
    ThreadPoolConfigDispatcherBuilder(createDispatcher, ThreadPoolConfig()).configure(
      conf_?(config getInt "keep-alive-time")(time ⇒ _.setKeepAliveTime(Duration(time, TIME_UNIT))),
      conf_?(config getDouble "core-pool-size-factor")(factor ⇒ _.setCorePoolSizeFromFactor(factor)),
      conf_?(config getDouble "max-pool-size-factor")(factor ⇒ _.setMaxPoolSizeFromFactor(factor)),
      conf_?(config getInt "executor-bounds")(bounds ⇒ _.setExecutorBounds(bounds)),
      conf_?(config getBool "allow-core-timeout")(allow ⇒ _.setAllowCoreThreadTimeout(allow)),
      conf_?(config getInt "task-queue-size" flatMap {
        case size if size > 0 =>
          config getString "task-queue-type" map {
            case "array" => ThreadPoolConfig.arrayBlockingQueue(size, false) //TODO config fairness?
            case "" | "linked" => ThreadPoolConfig.linkedBlockingQueue(size)
            case x => throw new IllegalArgumentException("[%s] is not a valid task-queue-type [array|linked]!" format x)
          }
        case _ => None
      })(queueFactory => _.setQueueFactory(queueFactory)),
      conf_?(config getString "rejection-policy" map {
        case "abort"          ⇒ new AbortPolicy()
        case "caller-runs"    ⇒ new CallerRunsPolicy()
        case "discard-oldest" ⇒ new DiscardOldestPolicy()
        case "discard"        ⇒ new DiscardPolicy()
        case x                ⇒ throw new IllegalArgumentException("[%s] is not a valid rejectionPolicy [abort|caller-runs|discard-oldest|discard]!" format x)
      })(policy ⇒ _.setRejectionPolicy(policy)))
  }
}
