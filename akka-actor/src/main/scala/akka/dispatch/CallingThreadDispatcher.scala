package akka.dispatch

import akka.actor.ActorRef
import java.util.concurrent.locks.ReentrantLock
import java.util.LinkedList
import java.util.concurrent.RejectedExecutionException
import akka.util.Switch
import java.lang.ref.WeakReference
import scala.annotation.tailrec

/*
 * Locking rules:
 *
 * While not suspended, messages are processed (!isActive) or queued
 * thread-locally (isActive). While suspended, messages are queued
 * thread-locally. When resuming, all messages are atomically scooped from all
 * non-active threads and queued on the resuming thread's queue, to be
 * processed immediately. Processing a queue checks suspend before each
 * invocation, leaving the active state if suspended. For this to work
 * reliably, the active flag needs to be set atomically with the initial check
 * for suspend. Scooping up messages means replacing the ThreadLocal's contents
 * with an empty new NestingQueue.
 *
 * All accesses to the queue must be done under the suspended-switch's lock, so
 * within one of its methods taking a closure argument.
 */

object CallingThreadDispatcher {
  private var queues = Map[CallingThreadMailbox, Set[WeakReference[NestingQueue]]]()

  // we have to forget about long-gone threads sometime
  private def gc {
    queues = queues mapValues (_ filter (_.get ne null)) filter (!_._2.isEmpty)
  }

  def registerQueue(mbox : CallingThreadMailbox, q : NestingQueue) : Unit = synchronized {
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
   * given mailbox. When this method returns, the queue will be entere
   * (active).
   */
  def gatherFromAllInactiveQueues(mbox : CallingThreadMailbox, own : NestingQueue) : Unit = synchronized {
    if (!own.isActive) own.enter
    if (queues contains mbox) {
      for {
        ref <- queues(mbox)
        q = ref.get
        if (q ne null) && !q.isActive
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
 * to run on the current thread unless the target actor is either suspended or
 * already running on the current thread (if it is running on a different
 * thread, then this thread will block until that other invocation is
 * finished); if the invocation is not run, it is queued in a thread-local
 * queue to be executed once the active invocation further up the call stack
 * finishes. This leads to completely deterministic execution order if only one
 * thread is used.
 *
 * Suspending and resuming are global actions for one actor, meaning they can
 * affect different threads, which leads to complications. If messages are
 * queued (thread-locally) during the suspended period, the only thread to run
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
class CallingThreadDispatcher(val warnings: Boolean = true) extends MessageDispatcher {
  import CallingThreadDispatcher._

  private[akka] override def createMailbox(actor: ActorRef) = new CallingThreadMailbox

  private def getMailbox(actor: ActorRef) = actor.mailbox.asInstanceOf[CallingThreadMailbox]

  private[akka] override def start {}

  private[akka] override def shutdown {}

  private[akka] override def timeoutMs = 0L

  override def suspend(actor: ActorRef) {
    getMailbox(actor).suspended.switchOn
  }

  override def resume(actor: ActorRef) {
    val mbox = getMailbox(actor)
    val queue = mbox.queue
    val wasActive = queue.isActive
    val switched = mbox.suspended.switchOff {
      gatherFromAllInactiveQueues(mbox, queue)
    }
    if (switched && !wasActive) {
      runQueue(mbox, queue)
    }
  }

  override def mailboxSize(actor: ActorRef) = getMailbox(actor).queue.size

  private[akka] override def dispatch(handle: MessageInvocation) {
    val mbox = getMailbox(handle.receiver)
    val queue = mbox.queue
    val execute = mbox.suspended.ifElseYield {
        queue.push(handle)
        if (warnings && handle.senderFuture.isDefined) {
          log.slf4j.warn("suspended, creating Future could deadlock; target: {}",
              handle.receiver)
        }
        false
      } {
        queue.push(handle)
        if (queue.isActive) {
          if (warnings && handle.senderFuture.isDefined) {
            log.slf4j.warn("blocked on this thread, creating Future could deadlock; target: {}",
                handle.receiver)
          }
          false
        } else {
          queue.enter
          true
        }
      }
    if (execute) runQueue(mbox, queue)
  }

  /*
   * This method must be called with this thread's queue, which must already
   * have been entered (active). When this method returns, the queue will be
   * inactive.
   *
   * If the catch block is executed, then a non-empty mailbox may be stalled as
   * there is no-one who cares to execute it before the next message is sent or
   * it is suspended and resumed.
   */
  private def runQueue(mbox : CallingThreadMailbox, queue : NestingQueue) {
    assert(queue.isActive)
    val handle = mbox.suspended.ifElseYield[MessageInvocation] {
        queue.leave
        null
      } {
        val ret = queue.pop
        if (ret eq null) queue.leave
        ret
      }
    if (handle ne null) {
      try {
        handle.invoke
        val f = handle.senderFuture
        if (warnings && f.isDefined && !f.get.isCompleted) {
          log.slf4j.warn("calling {} with message {} did not reply as expected, might deadlock", handle.receiver, handle.message)
        }
      } catch {
        case _ => queue.leave
      }
      runQueue(mbox, queue)
      log.info("runQueue")
    } else if (queue.isActive) {
      queue.leave
    }
  }
}

class NestingQueue {
  private var q = new LinkedList[MessageInvocation]()
  def size = q.size
  def push(handle : MessageInvocation) { q.offer(handle) }
  def peek = q.peek
  def pop = q.poll

  @volatile private var active = false
  def enter { if (active) error("already active") else active = true }
  def leave { if (!active) error("not active") else active = false }
  def isActive = active
}

class CallingThreadMailbox {

  private val q = new ThreadLocal[NestingQueue]() {
    override def initialValue = new NestingQueue
  }

  def queue = q.get

  val suspended = new Switch(false)
}
