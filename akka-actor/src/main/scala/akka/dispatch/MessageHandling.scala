/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
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
final case class MessageInvocation(val receiver: ActorInstance,
                                   val message: Any,
                                   val channel: UntypedChannel) {
  if (receiver eq null) throw new IllegalArgumentException("Receiver can't be null")

  final def invoke() {
    receiver invoke this
  }
}

final case class TaskInvocation(function: () ⇒ Unit, cleanup: () ⇒ Unit) extends Runnable {
  def run() {
    try {
      function()
    } catch {
      case e ⇒ EventHandler.error(e, this, e.getMessage)
    } finally {
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
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
abstract class MessageDispatcher extends Serializable {
  import MessageDispatcher._

  protected val uuids = new ConcurrentSkipListSet[Uuid]
  protected val _tasks = new AtomicLong(0L)
  protected val guard = new ReentrantGuard
  protected val active = new Switch(false)

  private var shutdownSchedule = UNSCHEDULED //This can be non-volatile since it is protected by guard withGuard

  /**
   *  Creates and returns a mailbox for the given actor.
   */
  protected[akka] def createMailbox(actor: ActorInstance): AnyRef

  /**
   * Name of this dispatcher.
   */
  def name: String

  /**
   * Attaches the specified actor instance to this dispatcher
   */
  final def attach(actor: ActorInstance) {
    guard withGuard {
      register(actor)
    }
    val promise = new ActorPromise(Timeout.never)(this)
    dispatchMessage(new MessageInvocation(actor, Init, promise))
    promise.get
  }

  /**
   * Detaches the specified actor instance from this dispatcher
   */
  final def detach(actor: ActorInstance) {
    guard withGuard {
      unregister(actor)
    }
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

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "attach" for the only invocation
   */
  protected[akka] def register(actor: ActorInstance) {
    if (actor.mailbox eq null)
      actor.mailbox = createMailbox(actor)

    uuids add actor.uuid
    if (active.isOff) {
      active.switchOn {
        start()
      }
    }
  }

  /**
   * Only "private[akka] for the sake of intercepting calls, DO NOT CALL THIS OUTSIDE OF THE DISPATCHER,
   * and only call it under the dispatcher-guard, see "detach" for the only invocation
   */
  protected[akka] def unregister(actor: ActorInstance) = {
    if (uuids remove actor.uuid) {
      cleanUpMailboxFor(actor)
      actor.mailbox = null
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
   * Overridable callback to clean up the mailbox for a given actor,
   * called when an actor is unregistered.
   */
  protected def cleanUpMailboxFor(actor: ActorInstance) {}

  /**
   * Traverses the list of actors (uuids) currently being attached to this dispatcher and stops those actors
   */
  def stopAllAttachedActors() {
    val i = uuids.iterator
    while (i.hasNext()) {
      val uuid = i.next()
      Actor.registry.local.actorFor(uuid) match {
        case Some(actor) ⇒ actor.stop()
        case None        ⇒
      }
    }
  }

  private val shutdownAction = new Runnable {
    def run() {
      guard withGuard {
        shutdownSchedule match {
          case RESCHEDULED ⇒
            shutdownSchedule = SCHEDULED
            Scheduler.scheduleOnce(this, timeoutMs, TimeUnit.MILLISECONDS)
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
  protected[akka] def timeoutMs: Long = Dispatchers.DEFAULT_SHUTDOWN_TIMEOUT.toMillis

  /**
   * After the call to this method, the dispatcher mustn't begin any new message processing for the specified reference
   */
  def suspend(actor: ActorInstance)

  /*
   * After the call to this method, the dispatcher must begin any new message processing for the specified reference
   */
  def resume(actor: ActorInstance)

  /**
   *   Will be called when the dispatcher is to queue an invocation for execution
   */
  protected[akka] def dispatch(invocation: MessageInvocation)

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
  def mailboxSize(actor: ActorInstance): Int

  /**
   * Returns the "current" emptiness status of the mailbox for the specified actor
   */
  def mailboxIsEmpty(actor: ActorInstance): Boolean

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
    else {
      val duration = Duration(
        config.getInt("mailbox-push-timeout-time", Dispatchers.MAILBOX_PUSH_TIME_OUT.toMillis.toInt),
        TIME_UNIT)
      BoundedMailbox(capacity, duration)
    }
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
