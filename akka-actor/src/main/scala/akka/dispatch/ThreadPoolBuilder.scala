/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.Collection
import java.util.concurrent._
import atomic.{ AtomicLong, AtomicInteger }
import akka.util.Duration
import akka.event.Logging.{ Warning, Error }
import akka.actor.ActorSystem
import java.util.concurrent.ThreadPoolExecutor.{ AbortPolicy }

object ThreadPoolConfig {
  type Bounds = Int
  type FlowHandler = Either[RejectedExecutionHandler, Bounds]
  type QueueFactory = () ⇒ BlockingQueue[Runnable]

  val defaultAllowCoreThreadTimeout: Boolean = false
  val defaultCorePoolSize: Int = 16
  val defaultMaxPoolSize: Int = 128
  val defaultTimeout: Duration = Duration(60000L, TimeUnit.MILLISECONDS)
  def defaultFlowHandler: FlowHandler = flowHandler(new AbortPolicy)

  def flowHandler(rejectionHandler: RejectedExecutionHandler): FlowHandler = Left(rejectionHandler)
  def flowHandler(bounds: Int): FlowHandler = Right(bounds)

  def fixedPoolSize(size: Int): Int = size
  def scaledPoolSize(multiplier: Double): Int =
    (Runtime.getRuntime.availableProcessors * multiplier).ceil.toInt

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
case class ThreadPoolConfig(app: ActorSystem,
                            allowCorePoolTimeout: Boolean = ThreadPoolConfig.defaultAllowCoreThreadTimeout,
                            corePoolSize: Int = ThreadPoolConfig.defaultCorePoolSize,
                            maxPoolSize: Int = ThreadPoolConfig.defaultMaxPoolSize,
                            threadTimeout: Duration = ThreadPoolConfig.defaultTimeout,
                            flowHandler: ThreadPoolConfig.FlowHandler = ThreadPoolConfig.defaultFlowHandler,
                            queueFactory: ThreadPoolConfig.QueueFactory = ThreadPoolConfig.linkedBlockingQueue())
  extends ExecutorServiceFactoryProvider {
  final def createExecutorServiceFactory(name: String): ExecutorServiceFactory = new ExecutorServiceFactory {
    val threadFactory = new MonitorableThreadFactory(name)
    def createExecutorService: ExecutorService = flowHandler match {
      case Left(rejectHandler) ⇒
        val service = new ThreadPoolExecutor(corePoolSize, maxPoolSize, threadTimeout.length, threadTimeout.unit, queueFactory(), threadFactory, rejectHandler)
        service.allowCoreThreadTimeOut(allowCorePoolTimeout)
        service
      case Right(bounds) ⇒
        val service = new ThreadPoolExecutor(corePoolSize, maxPoolSize, threadTimeout.length, threadTimeout.unit, queueFactory(), threadFactory)
        service.allowCoreThreadTimeOut(allowCorePoolTimeout)
        new BoundedExecutorDecorator(app, service, bounds)
    }
  }
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
  def build = dispatcherFactory(config)

  def withNewBoundedThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity(bounds: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(flowHandler = flowHandler(bounds), queueFactory = linkedBlockingQueue()))

  def withNewThreadPoolWithCustomBlockingQueue(newQueueFactory: QueueFactory): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(flowHandler = defaultFlowHandler, queueFactory = newQueueFactory))

  def withNewThreadPoolWithCustomBlockingQueue(queue: BlockingQueue[Runnable]): ThreadPoolConfigDispatcherBuilder =
    withNewThreadPoolWithCustomBlockingQueue(reusableQueue(queue))

  def withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity: ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = linkedBlockingQueue(), flowHandler = defaultFlowHandler))

  def withNewThreadPoolWithLinkedBlockingQueueWithCapacity(capacity: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = linkedBlockingQueue(capacity), flowHandler = defaultFlowHandler))

  def withNewThreadPoolWithSynchronousQueueWithFairness(fair: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = synchronousQueue(fair), flowHandler = defaultFlowHandler))

  def withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(capacity: Int, fair: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = arrayBlockingQueue(capacity, fair), flowHandler = defaultFlowHandler))

  def setCorePoolSize(size: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(corePoolSize = size))

  def setMaxPoolSize(size: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(maxPoolSize = size))

  def setCorePoolSizeFromFactor(multiplier: Double): ThreadPoolConfigDispatcherBuilder =
    setCorePoolSize(scaledPoolSize(multiplier))

  def setMaxPoolSizeFromFactor(multiplier: Double): ThreadPoolConfigDispatcherBuilder =
    setMaxPoolSize(scaledPoolSize(multiplier))

  def setExecutorBounds(bounds: Int): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(flowHandler = flowHandler(bounds)))

  def setKeepAliveTimeInMillis(time: Long): ThreadPoolConfigDispatcherBuilder =
    setKeepAliveTime(Duration(time, TimeUnit.MILLISECONDS))

  def setKeepAliveTime(time: Duration): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(threadTimeout = time))

  def setRejectionPolicy(policy: RejectedExecutionHandler): ThreadPoolConfigDispatcherBuilder =
    setFlowHandler(flowHandler(policy))

  def setFlowHandler(newFlowHandler: FlowHandler): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(flowHandler = newFlowHandler))

  def setAllowCoreThreadTimeout(allow: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(allowCorePoolTimeout = allow))

  def setQueueFactory(newQueueFactory: QueueFactory): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(queueFactory = newQueueFactory))

  def configure(fs: Option[Function[ThreadPoolConfigDispatcherBuilder, ThreadPoolConfigDispatcherBuilder]]*): ThreadPoolConfigDispatcherBuilder = fs.foldLeft(this)((c, f) ⇒ f.map(_(c)).getOrElse(c))
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThreadFactory(val name: String, val daemonic: Boolean = false) extends ThreadFactory {
  protected val counter = new AtomicLong

  def newThread(runnable: Runnable) = {
    val t = new MonitorableThread(runnable, name)
    t.setDaemon(daemonic)
    t
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object MonitorableThread {
  val DEFAULT_NAME = "MonitorableThread".intern

  // FIXME use MonitorableThread.created and MonitorableThread.alive in monitoring
  val created = new AtomicInteger
  val alive = new AtomicInteger
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThread(runnable: Runnable, name: String)
  extends Thread(runnable, name + "-" + MonitorableThread.created.incrementAndGet) {

  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
    def uncaughtException(thread: Thread, cause: Throwable) = {}
  })

  override def run = {
    try {
      MonitorableThread.alive.incrementAndGet
      super.run
    } finally {
      MonitorableThread.alive.decrementAndGet
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BoundedExecutorDecorator(val app: ActorSystem, val executor: ExecutorService, bound: Int) extends ExecutorServiceDelegate {
  protected val semaphore = new Semaphore(bound)

  override def execute(command: Runnable) = {
    semaphore.acquire
    try {
      executor.execute(new Runnable() {
        def run = {
          try {
            command.run
          } finally {
            semaphore.release
          }
        }
      })
    } catch {
      case e: RejectedExecutionException ⇒
        app.eventStream.publish(Warning(this, e.toString))
        semaphore.release
      case e: Throwable ⇒
        app.eventStream.publish(Error(e, this, e.getMessage))
        throw e
    }
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
