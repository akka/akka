/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.event.EventHandler
import java.util.concurrent.locks.ReentrantLock
import java.util.LinkedList
import java.util.concurrent.RejectedExecutionException
import akka.util.Switch
import java.lang.ref.WeakReference
import scala.annotation.tailrec
import akka.actor.ActorCell
import akka.dispatch._
import akka.AkkaApplication

/*
 * Locking rules:
 *
 * While not suspendSwitch, messages are processed (!isActive) or queued
 * thread-locally (isActive). While suspendSwitch, messages are queued
 * thread-locally. When resuming, all messages are atomically scooped from all
 * non-active threads and queued on the resuming thread's queue, to be
 * processed immediately. Processing a queue checks suspend before each
 * invocation, leaving the active state if suspendSwitch. For this to work
 * reliably, the active flag needs to be set atomically with the initial check
 * for suspend. Scooping up messages means replacing the ThreadLocal's contents
 * with an empty new NestingQueue.
 *
 * All accesses to the queue must be done under the suspendSwitch-switch's lock, so
 * within one of its methods taking a closure argument.
 */

object CallingThreadDispatcher {

  // PRIVATE DATA

  private var queues = Map[CallingThreadMailbox, Set[WeakReference[NestingQueue]]]()

  // we have to forget about long-gone threads sometime
  private def gc {
    queues = queues mapValues (_ filter (_.get ne null)) filter (!_._2.isEmpty)
  }

  protected[akka] def registerQueue(mbox: CallingThreadMailbox, q: NestingQueue): Unit = synchronized {
    if (queues contains mbox) {
      val newSet = queues(mbox) + new WeakReference(q)
      queues += mbox -> newSet
    } else {
      queues += mbox -> Set(new WeakReference(q))
    }
    gc
  }

  /*
   * This method must be called with "own" being this thread's queue for the
   * given mailbox. When this method returns, the queue will be entered
   * (active).
   */
  protected[akka] def gatherFromAllInactiveQueues(mbox: CallingThreadMailbox, own: NestingQueue): Unit = synchronized {
    if (!own.isActive) own.enter
    if (queues contains mbox) {
      for {
        ref ← queues(mbox)
        q = ref.get
        if (q ne null) && !q.isActive
        /*
         * if q.isActive was false, then it cannot change to true while we are
         * holding the mbox.suspende.switch's lock under which we are currently
         * executing
         */
      } {
        while (q.peek ne null) {
          own.push(q.pop)
        }
      }
    }
  }
}

/**
 * Dispatcher which runs invocations on the current thread only. This
 * dispatcher does not create any new threads, but it can be used from
 * different threads concurrently for the same actor. The dispatch strategy is
 * to run on the current thread unless the target actor is either suspendSwitch or
 * already running on the current thread (if it is running on a different
 * thread, then this thread will block until that other invocation is
 * finished); if the invocation is not run, it is queued in a thread-local
 * queue to be executed once the active invocation further up the call stack
 * finishes. This leads to completely deterministic execution order if only one
 * thread is used.
 *
 * Suspending and resuming are global actions for one actor, meaning they can
 * affect different threads, which leads to complications. If messages are
 * queued (thread-locally) during the suspendSwitch period, the only thread to run
 * them upon resume is the thread actually calling the resume method. Hence,
 * all thread-local queues which are not currently being drained (possible,
 * since suspend-queue-resume might happen entirely during an invocation on a
 * different thread) are scooped up into the current thread-local queue which
 * is then executed. It is possible to suspend an actor from within its call
 * stack.
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class CallingThreadDispatcher(_app: AkkaApplication, val name: String = "calling-thread", val warnings: Boolean = true) extends MessageDispatcher(_app) {
  import CallingThreadDispatcher._

  protected[akka] override def createMailbox(actor: ActorCell) = new CallingThreadMailbox(this, actor)

  private def getMailbox(actor: ActorCell) = actor.mailbox.asInstanceOf[CallingThreadMailbox]

  protected[akka] override def start() {}

  protected[akka] override def shutdown() {}

  protected[akka] override def throughput = 0
  protected[akka] override def throughputDeadlineTime = 0
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = false

  protected[akka] override def timeoutMs = 100L

  override def suspend(actor: ActorCell) {
    getMailbox(actor).suspendSwitch.switchOn
  }

  override def resume(actor: ActorCell) {
    val mbox = getMailbox(actor)
    val queue = mbox.queue
    val wasActive = queue.isActive
    val switched = mbox.suspendSwitch.switchOff {
      gatherFromAllInactiveQueues(mbox, queue)
    }
    if (switched && !wasActive) {
      runQueue(mbox, queue)
    }
  }

  override def mailboxSize(actor: ActorCell) = getMailbox(actor).queue.size

  override def mailboxIsEmpty(actor: ActorCell): Boolean = getMailbox(actor).queue.isEmpty

  protected[akka] override def systemDispatch(receiver: ActorCell, message: SystemMessage) {
    val mbox = getMailbox(receiver)
    mbox.lock.lock
    try {
      receiver systemInvoke message
    } finally {
      mbox.lock.unlock
    }
  }

  protected[akka] override def dispatch(receiver: ActorCell, handle: Envelope) {
    val mbox = getMailbox(receiver)
    val queue = mbox.queue
    val execute = mbox.suspendSwitch.fold {
      queue.push(handle)
      if (warnings && handle.channel.isInstanceOf[Promise[_]]) {
        app.eventHandler.warning(this, "suspendSwitch, creating Future could deadlock; target: %s" format receiver)
      }
      false
    } {
      queue.push(handle)
      if (queue.isActive) {
        if (warnings && handle.channel.isInstanceOf[Promise[_]]) {
          app.eventHandler.warning(this, "blocked on this thread, creating Future could deadlock; target: %s" format receiver)
        }
        false
      } else {
        queue.enter
        true
      }
    }
    if (execute) runQueue(mbox, queue)
  }

  protected[akka] override def executeTask(invocation: TaskInvocation) { invocation.run }

  /*
   * This method must be called with this thread's queue, which must already
   * have been entered (active). When this method returns, the queue will be
   * inactive.
   *
   * If the catch block is executed, then a non-empty mailbox may be stalled as
   * there is no-one who cares to execute it before the next message is sent or
   * it is suspendSwitch and resumed.
   */
  @tailrec
  private def runQueue(mbox: CallingThreadMailbox, queue: NestingQueue) {
    assert(queue.isActive)
    mbox.lock.lock
    val recurse = try {
      val handle = mbox.suspendSwitch.fold[Envelope] {
        queue.leave
        null
      } {
        val ret = queue.pop
        if (ret eq null) queue.leave
        ret
      }
      if (handle ne null) {
        try {
          mbox.actor.invoke(handle)
          if (warnings) handle.channel match {
            case f: ActorPromise if !f.isCompleted ⇒
              app.eventHandler.warning(this, "calling %s with message %s did not reply as expected, might deadlock" format (mbox.actor, handle.message))
            case _ ⇒
          }
          true
        } catch {
          case e ⇒
            app.eventHandler.error(this, e)
            queue.leave
            false
        }
      } else if (queue.isActive) {
        queue.leave
        false
      } else false
    } finally {
      mbox.lock.unlock
    }
    if (recurse) {
      runQueue(mbox, queue)
    }
  }
}

class NestingQueue {
  private var q = new LinkedList[Envelope]()
  def size = q.size
  def isEmpty = q.isEmpty
  def push(handle: Envelope) { q.offer(handle) }
  def peek = q.peek
  def pop = q.poll

  @volatile
  private var active = false
  def enter { if (active) sys.error("already active") else active = true }
  def leave { if (!active) sys.error("not active") else active = false }
  def isActive = active
}

class CallingThreadMailbox(val dispatcher: MessageDispatcher, _receiver: ActorCell) extends Mailbox(_receiver) with DefaultSystemMessageQueue {

  private val q = new ThreadLocal[NestingQueue]() {
    override def initialValue = new NestingQueue
  }

  def queue = q.get

  val lock = new ReentrantLock
  val suspendSwitch = new Switch

  override def enqueue(msg: Envelope) {}
  override def dequeue() = null
  override def hasMessages = true
  override def numberOfMessages = 0
}
