/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.Collection
import scala.concurrent.{ Awaitable, BlockContext, CanAwait }
import scala.concurrent.duration.Duration
import scala.concurrent.forkjoin._
import java.util.concurrent.{
  ArrayBlockingQueue,
  BlockingQueue,
  Callable,
  ExecutorService,
  LinkedBlockingQueue,
  RejectedExecutionHandler,
  RejectedExecutionException,
  SynchronousQueue,
  TimeUnit,
  ThreadFactory,
  ThreadPoolExecutor
}
import java.util.concurrent.atomic.{ AtomicReference, AtomicLong, AtomicInteger }

object ThreadPoolConfig {
  type QueueFactory = () ⇒ BlockingQueue[Runnable]

  val defaultAllowCoreThreadTimeout: Boolean = false
  val defaultCorePoolSize: Int = 16
  val defaultMaxPoolSize: Int = 128
  val defaultTimeout: Duration = Duration(60000L, TimeUnit.MILLISECONDS)
  val defaultRejectionPolicy: RejectedExecutionHandler = new SaneRejectedExecutionHandler()
  val defaultMinimumNumberOfUnblockedThreads: Int = 0

  def scaledPoolSize(floor: Int, multiplier: Double, ceiling: Int): Int =
    math.min(math.max((Runtime.getRuntime.availableProcessors * multiplier).ceil.toInt, floor), ceiling)

  def arrayBlockingQueue(capacity: Int, fair: Boolean): QueueFactory = () ⇒ new ArrayBlockingQueue[Runnable](capacity, fair)

  def synchronousQueue(fair: Boolean): QueueFactory = () ⇒ new SynchronousQueue[Runnable](fair)

  def linkedBlockingQueue(): QueueFactory = () ⇒ new LinkedBlockingQueue[Runnable]()

  def linkedBlockingQueue(capacity: Int): QueueFactory = () ⇒ new LinkedBlockingQueue[Runnable](capacity)

  def reusableQueue(queue: BlockingQueue[Runnable]): QueueFactory = () ⇒ queue

  def reusableQueue(queueFactory: QueueFactory): QueueFactory = reusableQueue(queueFactory())

  /**
   * INTERNAL API
   */
  private[ThreadPoolConfig] final class BlockContextThreadFactory(
    delegate: ThreadFactory, minUnblockedThreads: Int) extends AtomicInteger(0) with ThreadFactory {

    @volatile var threadPoolExecutor: ThreadPoolExecutor = null
    override def newThread(runnable: Runnable): Thread =
      delegate.newThread(new Runnable with BlockContext {
        override def run(): Unit = BlockContext.withBlockContext(this) {
          incrementAndGet()
          try runnable.run() finally { decrementAndGet() }
        }

        override def blockOn[T](thunk: ⇒ T)(implicit permission: CanAwait): T = {
          @scala.annotation.tailrec def canBlock(): Boolean = {
            val pre = get()
            if (pre > 0 && pre > minUnblockedThreads) compareAndSet(pre, pre - 1) || canBlock()
            else {
              val tpe = threadPoolExecutor
              (tpe ne null) && tpe.prestartCoreThread() && canBlock() // Retry if we have a TPE and we booted a new Core Thread
            }
          }

          if (canBlock()) {
            try thunk finally incrementAndGet()
          } else throw new RejectedExecutionException(
            s"Blocking rejected due to insufficient unblocked threads in pool. Needs at least $minUnblockedThreads")
        }
      })
  }
}

/**
 * Function0 without the fun stuff (mostly for the sake of the Java API side of things)
 */
trait ExecutorServiceFactory {
  def createExecutorService(): ExecutorService
}

/**
 * Generic way to specify an ExecutorService to a Dispatcher, create it with the given name if desired
 */
trait ExecutorServiceFactoryProvider {
  def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory
}

/**
 * A small configuration DSL to create ThreadPoolExecutors that can be provided as an ExecutorServiceFactoryProvider to Dispatcher
 */
case class ThreadPoolConfig(allowCorePoolTimeout: Boolean = ThreadPoolConfig.defaultAllowCoreThreadTimeout,
                            corePoolSize: Int = ThreadPoolConfig.defaultCorePoolSize,
                            maxPoolSize: Int = ThreadPoolConfig.defaultMaxPoolSize,
                            threadTimeout: Duration = ThreadPoolConfig.defaultTimeout,
                            queueFactory: ThreadPoolConfig.QueueFactory = ThreadPoolConfig.linkedBlockingQueue(),
                            rejectionPolicy: RejectedExecutionHandler = ThreadPoolConfig.defaultRejectionPolicy,
                            minUnblockedThreads: Int = ThreadPoolConfig.defaultMinimumNumberOfUnblockedThreads)
  extends ExecutorServiceFactoryProvider {

  class ThreadPoolExecutorServiceFactory(val threadFactory: ThreadFactory) extends ExecutorServiceFactory {
    def createExecutorService: ExecutorService = {
      val newThreadFactory: ThreadFactory =
        if (minUnblockedThreads > 0) new ThreadPoolConfig.BlockContextThreadFactory(threadFactory, minUnblockedThreads)
        else threadFactory

      val service: ThreadPoolExecutor = new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        threadTimeout.length,
        threadTimeout.unit,
        queueFactory(),
        newThreadFactory,
        rejectionPolicy) with LoadMetrics {
        def atFullThrottle(): Boolean = this.getActiveCount >= this.getPoolSize
      }
      newThreadFactory match {
        case bctf: ThreadPoolConfig.BlockContextThreadFactory ⇒
          bctf.threadPoolExecutor = service // Resolve the chicken-and-egg situation
          service.prestartAllCoreThreads() // Make sure we boot the core threads up so they are ready for blocking
        case other ⇒ // The Dude Abides
      }
      service.allowCoreThreadTimeOut(allowCorePoolTimeout)
      service
    }
  }
  final def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = {
    val tf = threadFactory match {
      // add the dispatcher id to the thread names and get
      case m: MonitorableThreadFactory ⇒ m.copy(name = s"${m.name}-${id}")
      case other                       ⇒ other
    }
    new ThreadPoolExecutorServiceFactory(tf)
  }
}

/**
 * A DSL to configure and create a MessageDispatcher with a ThreadPoolExecutor
 */
final case class ThreadPoolConfigBuilder(config: ThreadPoolConfig) {
  import ThreadPoolConfig._

  def withNewThreadPoolWithCustomBlockingQueue(newQueueFactory: QueueFactory): ThreadPoolConfigBuilder =
    copy(config = config.copy(queueFactory = newQueueFactory))

  def withNewThreadPoolWithCustomBlockingQueue(queue: BlockingQueue[Runnable]): ThreadPoolConfigBuilder =
    withNewThreadPoolWithCustomBlockingQueue(reusableQueue(queue))

  def withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity: ThreadPoolConfigBuilder =
    copy(config = config.copy(queueFactory = linkedBlockingQueue()))

  def withNewThreadPoolWithLinkedBlockingQueueWithCapacity(capacity: Int): ThreadPoolConfigBuilder =
    copy(config = config.copy(queueFactory = linkedBlockingQueue(capacity)))

  def withNewThreadPoolWithSynchronousQueueWithFairness(fair: Boolean): ThreadPoolConfigBuilder =
    copy(config = config.copy(queueFactory = synchronousQueue(fair)))

  def withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(capacity: Int, fair: Boolean): ThreadPoolConfigBuilder =
    copy(config = config.copy(queueFactory = arrayBlockingQueue(capacity, fair)))

  def setCorePoolSize(size: Int): ThreadPoolConfigBuilder =
    if (config.corePoolSize == size) this
    else if (config.maxPoolSize < size) this.copy(config = config.copy(corePoolSize = size, maxPoolSize = size))
    else copy(config = config.copy(corePoolSize = size))

  def setMaxPoolSize(size: Int): ThreadPoolConfigBuilder =
    if (config.maxPoolSize == size) this
    else if (config.corePoolSize > size) copy(config = config.copy(corePoolSize = size, maxPoolSize = size))
    else copy(config = config.copy(maxPoolSize = size))

  def setCorePoolSizeFromFactor(min: Int, multiplier: Double, max: Int): ThreadPoolConfigBuilder =
    setCorePoolSize(scaledPoolSize(min, multiplier, max))

  def setMaxPoolSizeFromFactor(min: Int, multiplier: Double, max: Int): ThreadPoolConfigBuilder =
    setMaxPoolSize(scaledPoolSize(min, multiplier, max))

  def setKeepAliveTimeInMillis(time: Long): ThreadPoolConfigBuilder =
    setKeepAliveTime(Duration(time, TimeUnit.MILLISECONDS))

  def setKeepAliveTime(time: Duration): ThreadPoolConfigBuilder =
    if (config.threadTimeout == time) this
    else copy(config = config.copy(threadTimeout = time))

  def setAllowCoreThreadTimeout(allow: Boolean): ThreadPoolConfigBuilder =
    if (config.allowCorePoolTimeout == allow) this
    else copy(config = config.copy(allowCorePoolTimeout = allow))

  def setQueueFactory(newQueueFactory: QueueFactory): ThreadPoolConfigBuilder =
    if (config.queueFactory eq newQueueFactory) this
    else copy(config = config.copy(queueFactory = newQueueFactory))

  def setMinimumNumberOfUnblockedThreads(newMinimumNumberOfUnblockedThreads: Int): ThreadPoolConfigBuilder =
    if (config.minUnblockedThreads == newMinimumNumberOfUnblockedThreads) this
    else copy(config = config.copy(minUnblockedThreads = newMinimumNumberOfUnblockedThreads))

  def configure(fs: Option[Function[ThreadPoolConfigBuilder, ThreadPoolConfigBuilder]]*): ThreadPoolConfigBuilder =
    fs.foldLeft(this)((c, f) ⇒ f.map(_(c)).getOrElse(c))
}

object MonitorableThreadFactory {
  val doNothing: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() { def uncaughtException(thread: Thread, cause: Throwable) = () }

  private[akka] class AkkaForkJoinWorkerThread(_pool: ForkJoinPool) extends ForkJoinWorkerThread(_pool) with BlockContext {
    override def blockOn[T](thunk: ⇒ T)(implicit permission: CanAwait): T = {
      @volatile var result: Option[T] = None
      ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker {
        def block(): Boolean = {
          result = Some(thunk)
          true
        }
        def isReleasable = result.isDefined
      })
      result.get // Exception intended if None
    }
  }
}

/**
 * Has mutable state, which is uncommon for case classes. Create a copy to get a clean slate.
 */
final case class MonitorableThreadFactory(name: String,
                                          daemonic: Boolean,
                                          contextClassLoader: Option[ClassLoader],
                                          exceptionHandler: Thread.UncaughtExceptionHandler = MonitorableThreadFactory.doNothing,
                                          protected val counter: AtomicLong = new AtomicLong(0L))
  extends ThreadFactory with ForkJoinPool.ForkJoinWorkerThreadFactory {

  def newThread(pool: ForkJoinPool): ForkJoinWorkerThread =
    wire(new MonitorableThreadFactory.AkkaForkJoinWorkerThread(pool))

  def newThread(runnable: Runnable): Thread =
    wire(new Thread(runnable))

  @deprecated("use the copy method instead", "2.4")
  def withName(newName: String): MonitorableThreadFactory = copy(newName)

  protected def wire[T <: Thread](t: T): T = {
    t.setName(s"${name}-${counter.incrementAndGet()}")
    t.setUncaughtExceptionHandler(exceptionHandler)
    t.setDaemon(daemonic)
    contextClassLoader foreach t.setContextClassLoader
    t
  }
}

/**
 * As the name says
 */
trait ExecutorServiceDelegate extends ExecutorService {

  def executor: ExecutorService

  def execute(command: Runnable) = executor.execute(command)

  def shutdown() { executor.shutdown() }

  def shutdownNow() = executor.shutdownNow()

  def isShutdown = executor.isShutdown

  def isTerminated = executor.isTerminated

  def awaitTermination(l: Long, timeUnit: TimeUnit) = executor.awaitTermination(l, timeUnit)

  def submit[T](callable: Callable[T]) = executor.submit(callable)

  def submit[T](runnable: Runnable, t: T) = executor.submit(runnable, t)

  def submit(runnable: Runnable) = executor.submit(runnable)

  def invokeAll[T](callables: Collection[_ <: Callable[T]]) = executor.invokeAll(callables)

  def invokeAll[T](callables: Collection[_ <: Callable[T]], l: Long, timeUnit: TimeUnit) = executor.invokeAll(callables, l, timeUnit)

  def invokeAny[T](callables: Collection[_ <: Callable[T]]) = executor.invokeAny(callables)

  def invokeAny[T](callables: Collection[_ <: Callable[T]], l: Long, timeUnit: TimeUnit) = executor.invokeAny(callables, l, timeUnit)
}

/**
 * The RejectedExecutionHandler used by Akka, it improves on CallerRunsPolicy
 * by throwing a RejectedExecutionException if the executor isShutdown.
 * (CallerRunsPolicy silently discards the runnable in this case, which is arguably broken)
 */
class SaneRejectedExecutionHandler extends RejectedExecutionHandler {
  def rejectedExecution(runnable: Runnable, threadPoolExecutor: ThreadPoolExecutor): Unit = {
    if (threadPoolExecutor.isShutdown) throw new RejectedExecutionException("Shutdown")
    else runnable.run()
  }
}
