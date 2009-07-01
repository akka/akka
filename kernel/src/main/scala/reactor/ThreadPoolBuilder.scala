/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent._
import atomic.{AtomicLong, AtomicInteger}
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.Collection
 
/**
 * Scala API.
 * <p/>
 * Example usage:
 * <pre/>
 *   val threadPool = ThreadPoolBuilder.newBuilder
 *     .newThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy)
 *     .build
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object ThreadPoolBuilder {
  def newBuilder = new ThreadPoolBuilder
}

/**
 * Java API.
 * <p/>
 * Example usage:
 * <pre/>
 *   ThreadPoolBuilder builder = new ThreadPoolBuilder();
 *   Executor threadPool =
 *      builder
 *     .newThreadPoolWithBoundedBlockingQueue(100)
 *     .setCorePoolSize(16)
 *     .setMaxPoolSize(128)
 *     .setKeepAliveTimeInMillis(60000)
 *     .setRejectionPolicy(new CallerRunsPolicy())
 *     .build();
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadPoolBuilder {
  val NR_START_THREADS = 16
  val NR_MAX_THREADS = 128
  val KEEP_ALIVE_TIME = 60000L // default is one minute
  val MILLISECONDS = TimeUnit.MILLISECONDS

  private var inProcessOfBuilding = false
  private var threadPool: ThreadPoolExecutor = _
  private val threadFactory = new MonitorableThreadFactory("akka")
  private var boundedExecutorBound = -1

  def build: ExecutorService = synchronized {
    inProcessOfBuilding = false
    if (boundedExecutorBound > 0) {
      val executor = new BoundedExecutorDecorator(threadPool, boundedExecutorBound)
      boundedExecutorBound = -1
      executor
    } else threadPool
  }

  def newThreadPool(queue: BlockingQueue[Runnable]) = synchronized {
    verifyNotInConstructionPhase
    threadPool = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, queue)
    this
  }

  /**
   * Creates an new thread pool in which the number of tasks in the pending queue is bounded. Will block when exceeeded.
   * <p/>
   * The 'bound' variable should specify the number equal to the size of the thread pool PLUS the number of queued tasks that should be followed.
   */
  def newThreadPoolWithBoundedBlockingQueue(bound: Int) = synchronized {
    verifyNotInConstructionPhase
    threadPool = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, new LinkedBlockingQueue[Runnable], threadFactory)
    boundedExecutorBound = bound
    this
  }

  /**
   * Negative or zero capacity creates an unbounded task queue.
   */
  def newThreadPoolWithLinkedBlockingQueueWithCapacity(capacity: Int) = synchronized {
    verifyNotInConstructionPhase
    val queue = if (capacity < 1) new LinkedBlockingQueue[Runnable] else new LinkedBlockingQueue[Runnable](capacity)
    threadPool = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, queue, threadFactory, new CallerRunsPolicy)
    this
  }

  def newThreadPoolWithSynchronousQueueWithFairness(fair: Boolean) = synchronized {
    verifyNotInConstructionPhase
    threadPool = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, new SynchronousQueue[Runnable](fair), threadFactory, new CallerRunsPolicy)
    this
  }

  def newThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(capacity: Int, fair: Boolean) = synchronized {
    verifyNotInConstructionPhase
    threadPool = new ThreadPoolExecutor(NR_START_THREADS, NR_MAX_THREADS, KEEP_ALIVE_TIME, MILLISECONDS, new ArrayBlockingQueue[Runnable](capacity, fair), threadFactory, new CallerRunsPolicy)
    this
  }

  /**
   * Default is 16.
   */
  def setCorePoolSize(size: Int) = synchronized {
    verifyInConstructionPhase
    threadPool.setCorePoolSize(size)
    this
  }

  /**
   * Default is 128.
   */
  def setMaxPoolSize(size: Int) = synchronized {
    verifyInConstructionPhase
    threadPool.setMaximumPoolSize(size)
    this
  }

  /**
   * Default is 60000 (one minute).
   */
  def setKeepAliveTimeInMillis(time: Long) = synchronized {
    verifyInConstructionPhase
    threadPool.setKeepAliveTime(time, MILLISECONDS)
    this
  }

  /**
   * Default ThreadPoolExecutor.CallerRunsPolicy. To allow graceful backing off when pool is overloaded.
   */
  def setRejectionPolicy(policy: RejectedExecutionHandler) = synchronized {
    verifyInConstructionPhase
    threadPool.setRejectedExecutionHandler(policy)
    this
  }

  private def verifyNotInConstructionPhase = {
    if (inProcessOfBuilding) throw new IllegalStateException("Is already in the process of building a thread pool")
    inProcessOfBuilding = true
  }

  private def verifyInConstructionPhase = {
    if (!inProcessOfBuilding) throw new IllegalStateException("Is not in the process of building a thread pool, start building one by invoking one of the 'newThreadPool*' methods")
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class BoundedExecutorDecorator(val executor: ExecutorService, bound: Int) extends ExecutorService {
  private val semaphore = new Semaphore(bound)

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
  def invokeAll[T](callables: Collection[Callable[T]]) = executor.invokeAll(callables)
  def invokeAll[T](callables: Collection[Callable[T]], l: Long, timeUnit: TimeUnit) = executor.invokeAll(callables, l, timeUnit)
  def invokeAny[T](callables: Collection[Callable[T]]) = executor.invokeAny(callables)
  def invokeAny[T](callables: Collection[Callable[T]], l: Long, timeUnit: TimeUnit) = executor.invokeAny(callables, l, timeUnit)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThreadFactory(val name: String) extends ThreadFactory {
  private val counter = new AtomicLong 
  def newThread(runnable: Runnable) =
    //new MonitorableThread(runnable, name)
    new Thread(runnable, name + "-" + counter.getAndIncrement)
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object MonitorableThread {
  val DEFAULT_NAME = "MonitorableThread"
  val created = new AtomicInteger
  val alive = new AtomicInteger
  @volatile val debugLifecycle = false
}

import kernel.util.Logging
/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class MonitorableThread(runnable: Runnable, name: String)
  extends Thread(runnable, name + "-" + MonitorableThread.created.incrementAndGet) {//with Logging {
  setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
    def uncaughtException(thread: Thread, cause: Throwable) = {} //log.error("UNCAUGHT in thread [%s] cause [%s]", thread.getName, cause)
  })

  override def run = {
    val debug = MonitorableThread.debugLifecycle
    //if (debug) log.debug("Created %s", getName)
    try {
       MonitorableThread.alive.incrementAndGet
       super.run
     } finally {
        MonitorableThread.alive.decrementAndGet
        //if (debug) log.debug("Exiting %s", getName)
      }
   }
}
