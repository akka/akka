/**
 *  Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.dispatch.affinity

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType.methodType
import java.util.Collections
import java.util.concurrent.TimeUnit.MICROSECONDS
import java.util.concurrent._
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport
import java.lang.Integer.reverseBytes

import akka.dispatch._
import akka.util.Helpers.Requiring
import com.typesafe.config.Config

import akka.annotation.{ InternalApi, ApiMayChange }
import akka.event.Logging
import akka.util.{ ImmutableIntMap, OptionVal, ReentrantGuard }

import scala.annotation.{ tailrec, switch }
import scala.collection.mutable
import scala.util.control.NonFatal

@InternalApi
@ApiMayChange
private[affinity] object AffinityPool {
  type PoolState = Int
  // PoolState: waiting to be initialized
  final val Uninitialized = 0
  // PoolState: currently in the process of initializing
  final val Initializing = 1
  // PoolState: accepts new tasks and processes tasks that are enqueued
  final val Running = 2
  // PoolState: does not accept new tasks, processes tasks that are in the queue
  final val ShuttingDown = 3
  // PoolState: does not accept new tasks, does not process tasks in queue
  final val ShutDown = 4
  // PoolState: all threads have been stopped, does not process tasks and does not accept new ones
  final val Terminated = 5

  // Method handle to JDK9+ onSpinWait method
  private val onSpinWaitMethodHandle =
    try
      OptionVal.Some(MethodHandles.lookup.findStatic(classOf[Thread], "onSpinWait", methodType(classOf[Unit])))
    catch {
      case NonFatal(_) ⇒ OptionVal.None
    }

  type IdleState = Int
  // IdleState: Initial state
  final val Initial = 0
  // IdleState: Spinning
  final val Spinning = 1
  // IdleState: Yielding
  final val Yielding = 2
  // IdleState: Parking
  final val Parking = 3

  // Following are auxiliary class and trait definitions
  private final class IdleStrategy(idleCpuLevel: Int) {

    private[this] val maxSpins = 1100 * idleCpuLevel - 1000
    private[this] val maxYields = 5 * idleCpuLevel
    private[this] val minParkPeriodNs = 1
    private[this] val maxParkPeriodNs = MICROSECONDS.toNanos(250 - ((80 * (idleCpuLevel - 1)) / 3))

    private[this] var state: IdleState = Initial
    private[this] var turns = 0L
    private[this] var parkPeriodNs = 0L

    @inline private[this] final def transitionTo(newState: IdleState): Unit = {
      state = newState
      turns = 0
    }

    def idle(): Unit = {
      (state: @switch) match {
        case Initial ⇒
          transitionTo(Spinning)
        case Spinning ⇒
          onSpinWaitMethodHandle match {
            case OptionVal.Some(m) ⇒ m.invokeExact()
            case OptionVal.None    ⇒
          }
          turns += 1
          if (turns > maxSpins)
            transitionTo(Yielding)
        case Yielding ⇒
          turns += 1
          if (turns > maxYields) {
            parkPeriodNs = minParkPeriodNs
            transitionTo(Parking)
          } else Thread.`yield`()
        case Parking ⇒
          LockSupport.parkNanos(parkPeriodNs)
          parkPeriodNs = Math.min(parkPeriodNs << 1, maxParkPeriodNs)
      }
    }

    final def reset(): Unit = transitionTo(Initial)
  }

  private final class BoundedAffinityTaskQueue(capacity: Int) extends AbstractBoundedNodeQueue[Runnable](capacity)
}

/**
 * An [[ExecutorService]] implementation which pins actor to particular threads
 * and guaranteed that an actor's [[Mailbox]] will e run on the thread it used
 * it used to run. In situations where we see a lot of cache ping pong, this
 * might lead to significant performance improvements.
 *
 * INTERNAL API
 */
@InternalApi
@ApiMayChange
private[akka] class AffinityPool(
  id:                                  String,
  parallelism:                         Int,
  affinityGroupSize:                   Int,
  threadFactory:                       ThreadFactory,
  idleCpuLevel:                        Int,
  final val fairDistributionThreshold: Int,
  rejectionHandler:                    RejectionHandler)
  extends AbstractExecutorService {

  if (parallelism <= 0)
    throw new IllegalArgumentException("Size of pool cannot be less or equal to 0")

  import AffinityPool._

  // Held while starting/shutting down workers/pool in order to make
  // the operations linear and enforce atomicity. An example of that would be
  // adding a worker. We want the creation of the worker, addition
  // to the set and starting to worker to be an atomic action. Using
  // a concurrent set would not give us that
  private val bookKeepingLock = new ReentrantGuard()

  // condition used for awaiting termination
  private val terminationCondition = bookKeepingLock.newCondition()

  // indicates the current state of the pool
  @volatile final private var poolState: PoolState = Uninitialized

  private final val workQueues = Array.fill(parallelism)(new BoundedAffinityTaskQueue(affinityGroupSize))
  private final val workers = mutable.Set[AffinityPoolWorker]()

  // maps a runnable to an index of a worker queue
  private[this] final val hashCache = new AtomicReference(ImmutableIntMap.empty)

  private def getQueueForRunnable(command: Runnable): BoundedAffinityTaskQueue = {
    val runnableHash = command.hashCode()

    def indexFor(h: Int): Int =
      Math.abs(reverseBytes(h * 0x9e3775cd) * 0x9e3775cd) % parallelism // In memory of Phil Bagwell

    val workQueueIndex =
      if (fairDistributionThreshold == 0)
        indexFor(runnableHash)
      else {
        @tailrec
        def cacheLookup(prev: ImmutableIntMap, hash: Int): Int = {
          val existingIndex = prev.get(runnableHash)
          if (existingIndex >= 0) existingIndex
          else if (prev.size > fairDistributionThreshold) indexFor(hash)
          else {
            val index = prev.size % parallelism
            if (hashCache.compareAndSet(prev, prev.updated(runnableHash, index)))
              index // Successfully added key
            else
              cacheLookup(hashCache.get(), hash) // Try again
          }
        }

        cacheLookup(hashCache.get(), runnableHash)
      }

    workQueues(workQueueIndex)
  }

  def start(): this.type =
    bookKeepingLock.withGuard {
      if (poolState == Uninitialized) {
        poolState = Initializing
        workQueues.foreach(q ⇒ addWorker(workers, q))
        poolState = Running
      }
      this
    }

  // WARNING: Only call while holding the bookKeepingLock
  private def addWorker(workers: mutable.Set[AffinityPoolWorker], q: BoundedAffinityTaskQueue): Unit = {
    val worker = new AffinityPoolWorker(q, new IdleStrategy(idleCpuLevel))
    workers.add(worker)
    worker.start()
  }

  /**
   * Each worker should go through that method while terminating.
   * In turn each worker is responsible for modifying the pool
   * state accordingly. For example if this is the last worker
   * and the queue is empty and we are in a ShuttingDown state
   * the worker can transition the pool to ShutDown and attempt
   * termination
   *
   * Furthermore, if this worker has experienced abrupt termination
   * due to an exception being thrown in user code, the worker is
   * responsible for adding one more worker to compensate for its
   * own termination
   *
   */
  private def onWorkerExit(w: AffinityPoolWorker, abruptTermination: Boolean): Unit =
    bookKeepingLock.withGuard {
      workers.remove(w)
      if (abruptTermination && poolState == Running)
        addWorker(workers, w.q)
      else if (workers.isEmpty && !abruptTermination && poolState >= ShuttingDown) {
        poolState = ShutDown // transition to shutdown and try to transition to termination
        attemptPoolTermination()
      }
    }

  override def execute(command: Runnable): Unit = {
    val queue = getQueueForRunnable(command) // Will throw NPE if command is null
    if (poolState >= ShuttingDown || !queue.add(command))
      rejectionHandler.reject(command, this)
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
    // recurse until pool is terminated or time out reached
    @tailrec
    def awaitTermination(nanos: Long): Boolean = {
      if (poolState == Terminated) true
      else if (nanos <= 0) false
      else awaitTermination(terminationCondition.awaitNanos(nanos))
    }

    bookKeepingLock.withGuard {
      // need to hold the lock to avoid monitor exception
      awaitTermination(unit.toNanos(timeout))
    }
  }

  // WARNING: Only call while holding the bookKeepingLock
  private def attemptPoolTermination(): Unit =
    if (workers.isEmpty && poolState == ShutDown) {
      poolState = Terminated
      terminationCondition.signalAll()
    }

  override def shutdownNow(): java.util.List[Runnable] =
    bookKeepingLock.withGuard {
      poolState = ShutDown
      workers.foreach(_.stop())
      attemptPoolTermination()
      // like in the FJ executor, we do not provide facility to obtain tasks that were in queue
      Collections.emptyList[Runnable]()
    }

  override def shutdown(): Unit =
    bookKeepingLock.withGuard {
      poolState = ShuttingDown
      // interrupts only idle workers.. so others can process their queues
      workers.foreach(_.stopIfIdle())
      attemptPoolTermination()
    }

  override def isShutdown: Boolean = poolState >= ShutDown

  override def isTerminated: Boolean = poolState == Terminated

  override def toString: String =
    s"${Logging.simpleName(this)}(id = $id, parallelism = $parallelism, affinityGroupSize = $affinityGroupSize, threadFactory = $threadFactory, idleCpuLevel = $idleCpuLevel, fairDistributionThreshold = $fairDistributionThreshold, rejectionHandler = $rejectionHandler)"

  private[this] final class AffinityPoolWorker( final val q: BoundedAffinityTaskQueue, final val idleStrategy: IdleStrategy) extends Runnable {
    final val thread: Thread = threadFactory.newThread(this)
    @volatile private[this] var executing: Boolean = false

    final def start(): Unit =
      if (thread eq null) throw new IllegalStateException(s"Was not able to allocate worker thread for ${AffinityPool.this}")
      else thread.start()

    override final def run(): Unit = {
      // Returns true if it executed something, false otherwise
      def executeNext(): Boolean = {
        val c = q.poll()
        if (c ne null) {
          executing = true
          try
            c.run()
          finally
            executing = false
          idleStrategy.reset()
          true
        } else {
          idleStrategy.idle() // if not wait for a bit
          false
        }
      }

      /**
       * We keep running as long as we are Running
       * or we're ShuttingDown but we still have tasks to execute,
       * and we're not interrupted.
       */
      @tailrec def runLoop(): Unit =
        if (!Thread.interrupted()) {
          (poolState: @switch) match {
            case Uninitialized ⇒ ()
            case Initializing | Running ⇒
              executeNext()
              runLoop()
            case ShuttingDown ⇒
              if (executeNext()) runLoop()
              else ()
            case ShutDown | Terminated ⇒ ()
          }
        }

      var abruptTermination = true
      try {
        runLoop()
        abruptTermination = false // if we have reached here, our termination is not due to an exception
      } finally {
        onWorkerExit(this, abruptTermination)
      }
    }

    def stop(): Unit = if (!thread.isInterrupted) thread.interrupt()

    def stopIfIdle(): Unit = if (!executing) stop()
  }
}

/**
 * INTERNAL API
 */
@InternalApi
@ApiMayChange
private[akka] final class AffinityPoolConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends ExecutorServiceConfigurator(config, prerequisites) {

  private final val MaxFairDistributionThreshold = 2048

  private val poolSize = ThreadPoolConfig.scaledPoolSize(
    config.getInt("parallelism-min"),
    config.getDouble("parallelism-factor"),
    config.getInt("parallelism-max"))
  private val taskQueueSize = config.getInt("task-queue-size")

  private val idleCpuLevel = config.getInt("idle-cpu-level").requiring(level ⇒
    1 <= level && level <= 10, "idle-cpu-level must be between 1 and 10")

  private val fairDistributionThreshold = config.getInt("fair-work-distribution-threshold").requiring(thr ⇒
    0 <= thr && thr <= MaxFairDistributionThreshold, s"fair-work-distribution-threshold must be between 0 and $MaxFairDistributionThreshold")

  private val rejectionHandlerFCQN = config.getString("rejection-handler-factory")

  private val rejectionHandlerFactory = prerequisites.dynamicAccess
    .createInstanceFor[RejectionHandlerFactory](rejectionHandlerFCQN, Nil).recover({
      case exception ⇒ throw new IllegalArgumentException(
        s"Cannot instantiate RejectionHandlerFactory (rejection-handler-factory = $rejectionHandlerFCQN), make sure it has an accessible empty constructor",
        exception)
    }).get

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory =
    new ExecutorServiceFactory {
      override def createExecutorService: ExecutorService =
        new AffinityPool(id, poolSize, taskQueueSize, threadFactory, idleCpuLevel, fairDistributionThreshold, rejectionHandlerFactory.create()).start()
    }
}

trait RejectionHandler {
  def reject(command: Runnable, service: ExecutorService)
}

trait RejectionHandlerFactory {
  def create(): RejectionHandler
}

/**
 * INTERNAL API
 */
@InternalApi
@ApiMayChange
private[akka] final class DefaultRejectionHandlerFactory extends RejectionHandlerFactory {
  private class DefaultRejectionHandler extends RejectionHandler {
    override def reject(command: Runnable, service: ExecutorService): Unit =
      throw new RejectedExecutionException(s"Task $command rejected from $service")
  }
  override def create(): RejectionHandler = new DefaultRejectionHandler()
}

