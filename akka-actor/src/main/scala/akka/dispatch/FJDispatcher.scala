package akka.dispatch

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

import akka.actor.ActorRef
import concurrent.forkjoin.{ ForkJoinWorkerThread, ForkJoinPool, ForkJoinTask }
import java.util.concurrent._
import java.lang.UnsupportedOperationException
import akka.event.EventHandler

/**
 * A Dispatcher that uses the ForkJoin library in scala.concurrent.forkjoin
 */
class FJDispatcher(
  name: String,
  throughput: Int = Dispatchers.THROUGHPUT,
  throughputDeadlineTime: Int = Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS,
  mailboxType: MailboxType = Dispatchers.MAILBOX_TYPE,
  forkJoinPoolConfig: ForkJoinPoolConfig = ForkJoinPoolConfig()) extends Dispatcher(name, throughput, throughputDeadlineTime, mailboxType, forkJoinPoolConfig) {

  def this(name: String, throughput: Int, throughputDeadlineTime: Int, mailboxType: MailboxType) =
    this(name, throughput, throughputDeadlineTime, mailboxType, ForkJoinPoolConfig()) // Needed for Java API usage

  def this(name: String, throughput: Int, mailboxType: MailboxType) =
    this(name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, mailboxType) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], throughput: Int) =
    this(name, throughput, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  def this(name: String, comparator: java.util.Comparator[MessageInvocation], forkJoinPoolConfig: ForkJoinPoolConfig) =
    this(name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE, forkJoinPoolConfig)

  def this(name: String, comparator: java.util.Comparator[MessageInvocation]) =
    this(name, Dispatchers.THROUGHPUT, Dispatchers.THROUGHPUT_DEADLINE_TIME_MILLIS, Dispatchers.MAILBOX_TYPE) // Needed for Java API usage

  override def createMailbox(actorRef: ActorRef): AnyRef = mailboxType match {
    case b: UnboundedMailbox ⇒
      new ConcurrentLinkedQueue[MessageInvocation] with MessageQueue with ExecutableMailbox with FJMailbox {
        @inline
        final def dispatcher = FJDispatcher.this
        @inline
        final def enqueue(m: MessageInvocation) = this.add(m)
        @inline
        final def dequeue(): MessageInvocation = this.poll()
      }
    case b: BoundedMailbox ⇒
      new DefaultBoundedMessageQueue(b.capacity, b.pushTimeOut) with ExecutableMailbox with FJMailbox {
        @inline
        final def dispatcher = FJDispatcher.this
      }
  }

  override private[akka] def doneProcessingMailbox(mbox: MessageQueue with ExecutableMailbox): Unit = {
    super.doneProcessingMailbox(mbox)
    ForkJoinTask.helpQuiesce()
  }
}

case class ForkJoinPoolConfig(targetParallelism: Int = Runtime.getRuntime.availableProcessors()) extends ExecutorServiceFactoryProvider {
  final def createExecutorServiceFactory(name: String): ExecutorServiceFactory = new ExecutorServiceFactory {
    def createExecutorService: ExecutorService = {
      new ForkJoinPool(targetParallelism) with ExecutorService {
        setAsyncMode(true)
        setMaintainsParallelism(true)

        override def execute(r: Runnable) {
          r match {
            case fjmbox: FJMailbox ⇒
              //fjmbox.fjTask.reinitialize()
              Thread.currentThread match {
                case fjwt: ForkJoinWorkerThread if fjwt.getPool eq this ⇒
                  fjmbox.fjTask.fork() //We should do fjwt.pushTask(fjmbox.fjTask) but it's package protected
                case _ ⇒ super.execute[Unit](fjmbox.fjTask)
              }
            case _ ⇒
              super.execute(r)
          }
        }

        import java.util.{ Collection ⇒ JCollection }

        def invokeAny[T](callables: JCollection[_ <: Callable[T]]) =
          throw new UnsupportedOperationException("invokeAny. NOT!")

        def invokeAny[T](callables: JCollection[_ <: Callable[T]], l: Long, timeUnit: TimeUnit) =
          throw new UnsupportedOperationException("invokeAny. NOT!")

        def invokeAll[T](callables: JCollection[_ <: Callable[T]], l: Long, timeUnit: TimeUnit) =
          throw new UnsupportedOperationException("invokeAny. NOT!")
      }
    }
  }
}

trait FJMailbox { self: ExecutableMailbox ⇒
  val fjTask = new ForkJoinTask[Unit] with Runnable {
    var result: Unit = ()
    def getRawResult() = result
    def setRawResult(v: Unit) { result = v }
    def exec() = {
      try { self.run() } catch { case t ⇒ EventHandler.error(t, self, "Exception in FJ Worker") }
      true
    }
    def run() { invoke() }
  }
}