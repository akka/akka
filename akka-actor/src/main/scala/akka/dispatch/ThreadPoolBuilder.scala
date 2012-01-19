/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.Collection
import java.util.concurrent.atomic.{ AtomicLong, AtomicInteger }
import akka.util.Duration
import java.util.concurrent._

object ThreadPoolConfig {
  type QueueFactory = () ⇒ BlockingQueue[Runnable]

  val defaultAllowCoreThreadTimeout: Boolean = false
  val defaultCorePoolSize: Int = 16
  val defaultMaxPoolSize: Int = 128
  val defaultTimeout: Duration = Duration(60000L, TimeUnit.MILLISECONDS)

  def scaledPoolSize(floor: Int, multiplier: Double, ceiling: Int): Int = {
    import scala.math.{ min, max }
    min(max((Runtime.getRuntime.availableProcessors * multiplier).ceil.toInt, floor), ceiling)
  }

  def arrayBlockingQueue(capacity: Int, fair: Boolean): QueueFactory =
    () ⇒ new ArrayBlockingQueue[Runnable](capacity, fair)

  def synchronousQueue(fair: Boolean): QueueFactory =
    () ⇒ new SynchronousQueue[Runnable](fair)

  def linkedBlockingQueue(): QueueFactory =
    () ⇒ new LinkedBlockingQueue[Runnable]()

  def linkedBlockingQueue(capacity: Int): QueueFactory =
    () ⇒ new LinkedBlockingQueue[Runnable](capacity)

  def reusableQueue(queue: BlockingQueue[Runnable]): QueueFactory =
    () ⇒ queue

  def reusableQueue(queueFactory: QueueFactory): QueueFactory = {
    val queue = queueFactory()
    () ⇒ queue
  }
}

/**
 * Function0 without the fun stuff (mostly for the sake of the Java API side of things)
 */
trait ExecutorServiceFactory {
  def createExecutorService: ExecutorService
}

/**
 * Generic way to specify an ExecutorService to a Dispatcher, create it with the given name if desired
 */
trait ExecutorServiceFactoryProvider {
  def createExecutorServiceFactory(name: String): ExecutorServiceFactory
}

/**
 * A small configuration DSL to create ThreadPoolExecutors that can be provided as an ExecutorServiceFactoryProvider to Dispatcher
 */
case class ThreadPoolConfig(allowCorePoolTimeout: Boolean = ThreadPoolConfig.defaultAllowCoreThreadTimeout,
                            corePoolSize: Int = ThreadPoolConfig.defaultCorePoolSize,
                            maxPoolSize: Int = ThreadPoolConfig.defaultMaxPoolSize,
                            threadTimeout: Duration = ThreadPoolConfig.defaultTimeout,
                            queueFactory: ThreadPoolConfig.QueueFactory = ThreadPoolConfig.linkedBlockingQueue(),
                            daemonic: Boolean = false)
  extends ExecutorServiceFactoryProvider {
  class ThreadPoolExecutorServiceFactory(val threadFactory: ThreadFactory) extends ExecutorServiceFactory {
    def createExecutorService: ExecutorService = {
      val service = new ThreadPoolExecutor(corePoolSize, maxPoolSize, threadTimeout.length, threadTimeout.unit, queueFactory(), threadFactory, new SaneRejectedExecutionHandler)
      service.allowCoreThreadTimeOut(allowCorePoolTimeout)
      service
    }
  }
  final def createExecutorServiceFactory(name: String): ExecutorServiceFactory = new ThreadPoolExecutorServiceFactory(new MonitorableThreadFactory(name, daemonic))
}

trait DispatcherBuilder {
  def build: MessageDispatcher
}

object ThreadPoolConfigDispatcherBuilder {
  def conf_?[T](opt: Option[T])(fun: (T) ⇒ ThreadPoolConfigDispatcherBuilder ⇒ ThreadPoolConfigDispatcherBuilder): Option[(ThreadPoolConfigDispatcherBuilder) ⇒ ThreadPoolConfigDispatcherBuilder] = opt map fun
}

/**
 * A DSL to configure and create a MessageDispatcher with a ThreadPoolExecutor
 */
case class ThreadPoolConfigDispatcherBuilder(dispatcherFactory: (ThreadPoolConfig) ⇒ MessageDispatcher, config: ThreadPoolConfig) extends DispatcherBuilder {
  import ThreadPoolConfig._
  def build: MessageDispatcher = dispatcherFactory(config)

  def withNewThreadPoolWithCustomBlockingQueue(newQueueFactory: QueueFactory): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = newQueueFactory))

  def withNewThreadPoolWithCustomBlockingQueue(queue: BlockingQueue[Runnable]): ThreadPoolConfigDispatcherBuilder =
    withNewThreadPoolWithCustomBlockingQueue(reusableQueue(queue))

  def withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity: ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = linkedBlockingQueue()))

  def withNewThreadPoolWithLinkedBlockingQueueWithCapacity(capacity: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = linkedBlockingQueue(capacity)))

  def withNewThreadPoolWithSynchronousQueueWithFairness(fair: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = synchronousQueue(fair)))

  def withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(capacity: Int, fair: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = arrayBlockingQueue(capacity, fair)))

  def setCorePoolSize(size: Int): ThreadPoolConfigDispatcherBuilder =
    if (config.maxPoolSize < size)
      this.copy(config = config.copy(corePoolSize = size, maxPoolSize = size))
    else
      this.copy(config = config.copy(corePoolSize = size))

  def setMaxPoolSize(size: Int): ThreadPoolConfigDispatcherBuilder =
    if (config.corePoolSize > size)
      this.copy(config = config.copy(corePoolSize = size, maxPoolSize = size))
    else
      this.copy(config = config.copy(maxPoolSize = size))

  def setCorePoolSizeFromFactor(min: Int, multiplier: Double, max: Int): ThreadPoolConfigDispatcherBuilder =
    setCorePoolSize(scaledPoolSize(min, multiplier, max))

  def setMaxPoolSizeFromFactor(min: Int, multiplier: Double, max: Int): ThreadPoolConfigDispatcherBuilder =
    setMaxPoolSize(scaledPoolSize(min, multiplier, max))

  def setKeepAliveTimeInMillis(time: Long): ThreadPoolConfigDispatcherBuilder =
    setKeepAliveTime(Duration(time, TimeUnit.MILLISECONDS))

  def setKeepAliveTime(time: Duration): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(threadTimeout = time))

  def setAllowCoreThreadTimeout(allow: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(allowCorePoolTimeout = allow))

  def setQueueFactory(newQueueFactory: QueueFactory): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = newQueueFactory))

  def configure(fs: Option[Function[ThreadPoolConfigDispatcherBuilder, ThreadPoolConfigDispatcherBuilder]]*): ThreadPoolConfigDispatcherBuilder = fs.foldLeft(this)((c, f) ⇒ f.map(_(c)).getOrElse(c))
}

class MonitorableThreadFactory(val name: String, val daemonic: Boolean = false) extends ThreadFactory {
  protected val counter = new AtomicLong
  protected val doNothing: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() {
      def uncaughtException(thread: Thread, cause: Throwable) = {}
    }

  def newThread(runnable: Runnable) = {
    val t = new Thread(runnable, name + counter.incrementAndGet())
    t.setUncaughtExceptionHandler(doNothing)
    t.setDaemon(daemonic)
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