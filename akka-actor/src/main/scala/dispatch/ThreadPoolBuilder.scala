/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dispatch

import java.util.Collection
import java.util.concurrent._
import atomic.{AtomicLong, AtomicInteger}
import ThreadPoolExecutor.CallerRunsPolicy

import se.scalablesolutions.akka.actor.IllegalActorStateException
import se.scalablesolutions.akka.util. {Duration, Logger, Logging}

object ThreadPoolConfig {
  type Bounds = Int
  type FlowHandler = Either[RejectedExecutionHandler,Bounds]
  type QueueFactory = () => BlockingQueue[Runnable]

  val defaultAllowCoreThreadTimeout: Boolean = false
  val defaultCorePoolSize: Int               = 16
  val defaultMaxPoolSize: Int                = 128
  val defaultTimeout: Duration               = Duration(60000L,TimeUnit.MILLISECONDS)
  def defaultFlowHandler: FlowHandler        = flowHandler(new CallerRunsPolicy)

  def flowHandler(rejectionHandler: RejectedExecutionHandler): FlowHandler = Left(rejectionHandler)
  def flowHandler(bounds: Int): FlowHandler = Right(bounds)

  def fixedPoolSize(size: Int): Int = size
  def scaledPoolSize(multiplier: Double): Int =
    (Runtime.getRuntime.availableProcessors * multiplier).ceil.toInt

  def arrayBlockingQueue(capacity: Int, fair: Boolean): QueueFactory =
    () => new ArrayBlockingQueue[Runnable](capacity,fair)

  def synchronousQueue(fair: Boolean): QueueFactory =
    () => new SynchronousQueue[Runnable](fair)

  def linkedBlockingQueue(): QueueFactory =
    () => new LinkedBlockingQueue[Runnable]()

  def linkedBlockingQueue(capacity: Int): QueueFactory =
    () => new LinkedBlockingQueue[Runnable](capacity)

  def reusableQueue(queue: BlockingQueue[Runnable]): QueueFactory =
    () => queue

  def reusableQueue(queueFactory: QueueFactory): QueueFactory = {
    val queue = queueFactory()
    () => queue
  }
}

case class ThreadPoolConfig(allowCorePoolTimeout: Boolean = ThreadPoolConfig.defaultAllowCoreThreadTimeout,
                            corePoolSize:         Int = ThreadPoolConfig.defaultCorePoolSize,
                            maxPoolSize:          Int = ThreadPoolConfig.defaultMaxPoolSize,
                            threadTimeout:        Duration = ThreadPoolConfig.defaultTimeout,
                            flowHandler:          ThreadPoolConfig.FlowHandler = ThreadPoolConfig.defaultFlowHandler,
                            queueFactory:         ThreadPoolConfig.QueueFactory = ThreadPoolConfig.linkedBlockingQueue()) {

  final def createExecutorService(threadFactory: ThreadFactory): ExecutorService = {
    flowHandler match {
      case Left(rejectHandler) =>
        val service = new ThreadPoolExecutor(corePoolSize, maxPoolSize, threadTimeout.length, threadTimeout.unit, queueFactory(), threadFactory, rejectHandler)
        service.allowCoreThreadTimeOut(allowCorePoolTimeout)
        service
      case Right(bounds) =>
        val service = new ThreadPoolExecutor(corePoolSize, maxPoolSize, threadTimeout.length, threadTimeout.unit, queueFactory(), threadFactory)
        service.allowCoreThreadTimeOut(allowCorePoolTimeout)
        new BoundedExecutorDecorator(service,bounds)
    }
  }
}

trait DispatcherBuilder {
  def build: MessageDispatcher
}

case class ThreadPoolConfigDispatcherBuilder(dispatcherFactory: (ThreadPoolConfig) => MessageDispatcher, config: ThreadPoolConfig) extends DispatcherBuilder {
  import ThreadPoolConfig._
  def build = dispatcherFactory(config)

  //TODO remove this, for backwards compat only
  def buildThreadPool = build

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
    this.copy(config = config.copy(queueFactory = arrayBlockingQueue(capacity,fair), flowHandler = defaultFlowHandler))

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
    setKeepAliveTime(Duration(time,TimeUnit.MILLISECONDS))

  def setKeepAliveTime(time: Duration): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(threadTimeout = time))

  def setRejectionPolicy(policy: RejectedExecutionHandler): ThreadPoolConfigDispatcherBuilder =
    setFlowHandler(flowHandler(policy))

  def setFlowHandler(newFlowHandler: FlowHandler): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(flowHandler = newFlowHandler))

  def setAllowCoreThreadTimeout(allow: Boolean): ThreadPoolConfigDispatcherBuilder =
    this.copy(config = config.copy(allowCorePoolTimeout = allow))
}


trait ThreadPoolBuilder extends Logging {
  val name: String

  private val NR_START_THREADS = 16
  private val NR_MAX_THREADS = 128
  private val KEEP_ALIVE_TIME = 60000L // default is one minute
  private val MILLISECONDS = TimeUnit.MILLISECONDS

  private var threadPoolBuilder: ThreadPoolExecutor = _
  private var boundedExecutorBound = -1
  protected var mailboxCapacity = -1
  @volatile private var inProcessOfBuilding = false
  private var blockingQueue: BlockingQueue[Runnable] = _

  protected lazy val threadFactory = new MonitorableThreadFactory(name)

  @volatile var executor: ExecutorService = _
  
  def isShutdown = executor.isShutdown

  def buildThreadPool(): ExecutorService = synchronized {
    ensureNotActive
    inProcessOfBuilding = false

    log.debug("Creating a %s with config [core-pool:%d,max-pool:%d,timeout:%d,allowCoreTimeout:%s,rejectPolicy:%s]",
      getClass.getName,
      threadPoolBuilder.getCorePoolSize,
      threadPoolBuilder.getMaximumPoolSize,
      threadPoolBuilder.getKeepAliveTime(MILLISECONDS),
      threadPoolBuilder.allowsCoreThreadTimeOut,
      threadPoolBuilder.getRejectedExecutionHandler.getClass.getSimpleName)

    if (boundedExecutorBound > 0) {
      val boundedExecutor = new BoundedExecutorDecorator(threadPoolBuilder, boundedExecutorBound)
      boundedExecutorBound = -1 //Why is this here?
      executor = boundedExecutor
    } else {
      executor = threadPoolBuilder
    }
    executor
  }

  def withNewThreadPoolWithCustomBlockingQueue(queue: BlockingQueue[Runnable]): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = queue
    threadPoolBuilder = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, queue)
    this
  }

  /**
   * Creates a new thread pool in which the number of tasks in the pending queue is bounded. Will block when exceeded.
   * <p/>
   * The 'bound' variable should specify the number equal to the size of the thread pool PLUS the number of queued tasks that should be followed.
   */
  def withNewBoundedThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity(bound: Int): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = new LinkedBlockingQueue[Runnable]
    threadPoolBuilder = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, blockingQueue, threadFactory)
    boundedExecutorBound = bound
    this
  }

  def withNewThreadPoolWithLinkedBlockingQueueWithUnboundedCapacity: ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = new LinkedBlockingQueue[Runnable]
    threadPoolBuilder = new ThreadPoolExecutor(
      NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, blockingQueue, threadFactory, new CallerRunsPolicy)
    this
  }

  def withNewThreadPoolWithLinkedBlockingQueueWithCapacity(capacity: Int): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = new LinkedBlockingQueue[Runnable](capacity)
    threadPoolBuilder = new ThreadPoolExecutor(
      NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, blockingQueue, threadFactory, new CallerRunsPolicy)
    this
  }

  def withNewThreadPoolWithSynchronousQueueWithFairness(fair: Boolean): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = new SynchronousQueue[Runnable](fair)
    threadPoolBuilder = new ThreadPoolExecutor(
      NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, blockingQueue, threadFactory, new CallerRunsPolicy)
    this
  }

  def withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(capacity: Int, fair: Boolean): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyNotInConstructionPhase
    blockingQueue = new ArrayBlockingQueue[Runnable](capacity, fair)
    threadPoolBuilder = new ThreadPoolExecutor(
      NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, blockingQueue, threadFactory, new CallerRunsPolicy)
    this
  }

  def configureIfPossible(f: (ThreadPoolBuilder) => Unit): Boolean = synchronized {
    if(inProcessOfBuilding) {
      f(this)
      true
    }
    else {
      log.warning("Tried to configure an already started ThreadPoolBuilder of type [%s]",getClass.getName)
      false
    }
  }

  /**
   * Default is 16.
   */
  def setCorePoolSize(size: Int): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setCorePoolSize(size))

  /**
   * Default is 128.
   */
  def setMaxPoolSize(size: Int): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setMaximumPoolSize(size))


  /**
   * Sets the core pool size to (availableProcessors * multipliers).ceil.toInt
   */
  def setCorePoolSizeFromFactor(multiplier: Double): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setCorePoolSize(procs(multiplier)))

  /**
   * Sets the max pool size to (availableProcessors * multipliers).ceil.toInt
   */
  def setMaxPoolSizeFromFactor(multiplier: Double): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setMaximumPoolSize(procs(multiplier)))

  /**
   * Sets the bound, -1 is unbounded
   */
  def setExecutorBounds(bounds: Int): Unit = synchronized {
    this.boundedExecutorBound = bounds
  }

  /**
   * Sets the mailbox capacity, -1 is unbounded
   */
  def setMailboxCapacity(capacity: Int): Unit = synchronized {
    this.mailboxCapacity = capacity
  }

  protected def procs(multiplier: Double): Int =
    (Runtime.getRuntime.availableProcessors * multiplier).ceil.toInt

  /**
   *  Default is 60000 (one minute).
   */
  def setKeepAliveTimeInMillis(time: Long): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setKeepAliveTime(time, MILLISECONDS))

  /**
   * Default ThreadPoolExecutor.CallerRunsPolicy. To allow graceful backing off when pool is overloaded.
   */
  def setRejectionPolicy(policy: RejectedExecutionHandler): ThreadPoolBuilder =
    setThreadPoolExecutorProperty(_.setRejectedExecutionHandler(policy))

  /**
   * Default false, set to true to conserve thread for potentially unused dispatchers
   */
  def setAllowCoreThreadTimeout(allow: Boolean) =
    setThreadPoolExecutorProperty(_.allowCoreThreadTimeOut(allow))

  /**
   * Default ThreadPoolExecutor.CallerRunsPolicy. To allow graceful backing off when pool is overloaded.
   */
  protected def setThreadPoolExecutorProperty(f: (ThreadPoolExecutor) => Unit): ThreadPoolBuilder = synchronized {
    ensureNotActive
    verifyInConstructionPhase
    f(threadPoolBuilder)
    this
  }


  protected def verifyNotInConstructionPhase = {
    if (inProcessOfBuilding) throw new IllegalActorStateException("Is already in the process of building a thread pool")
    inProcessOfBuilding = true
  }

  protected def verifyInConstructionPhase = {
    if (!inProcessOfBuilding) throw new IllegalActorStateException(
      "Is not in the process of building a thread pool, start building one by invoking one of the 'newThreadPool*' methods")
  }

  def ensureNotActive(): Unit
}


/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThreadFactory(val name: String) extends ThreadFactory {
  protected val counter = new AtomicLong

  def newThread(runnable: Runnable) =
  new MonitorableThread(runnable, name)
//    new Thread(runnable, name + "-" + counter.getAndIncrement)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object MonitorableThread {
  val DEFAULT_NAME = "MonitorableThread"
  val created = new AtomicInteger
  val alive = new AtomicInteger
  @volatile var debugLifecycle = false
}

// FIXME fix the issues with using the monitoring in MonitorableThread

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThread(runnable: Runnable, name: String)
    extends Thread(runnable, name + "-" + MonitorableThread.created.incrementAndGet) with Logging {

  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
    def uncaughtException(thread: Thread, cause: Throwable) =
      log.error(cause, "UNCAUGHT in thread [%s]", thread.getName)
  })

  override def run = {
    val debug = MonitorableThread.debugLifecycle
    log.debug("Created thread %s", getName)
    try {
      MonitorableThread.alive.incrementAndGet
      super.run
    } finally {
      MonitorableThread.alive.decrementAndGet
      log.debug("Exiting thread %s", getName)
    }
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BoundedExecutorDecorator(val executor: ExecutorService, bound: Int) extends ExecutorService with Logging {
  protected val semaphore = new Semaphore(bound)

  def execute(command: Runnable) = {
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
      case e: RejectedExecutionException =>
        semaphore.release
      case e =>
        log.error(e,"Unexpected exception")
        throw e
    }
  }

  // Delegating methods for the ExecutorService interface
  def shutdown = executor.shutdown

  def shutdownNow = executor.shutdownNow

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
