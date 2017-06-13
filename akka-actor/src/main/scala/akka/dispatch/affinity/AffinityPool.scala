/**
 *  Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.dispatch.affinity

import java.util
import java.util.Collections
import java.util.concurrent._
import java.util.concurrent.locks.{ Lock, LockSupport, ReentrantLock }

import akka.dispatch._
import akka.dispatch.affinity.AffinityPool._
import com.typesafe.config.Config
import net.openhft.affinity.{ AffinityStrategies, AffinityThreadFactory }

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable

class AffinityPool(parallelism: Int, affinityGroupSize: Int, tf: ThreadFactory, waitingStrategy: WaitingStrategy) extends AbstractExecutorService {

  if (parallelism <= 0)
    throw new IllegalArgumentException("Size of pool cannot be less or equal to 0")

  // Held while starting/shutting down workers/pool in order to make
  // the operations linear and enforce atomicity. An example of that would be
  // adding a worker. We want the creation of the worker, addition
  // to the set and starting to worker to be an atomic action. Using
  // a concurrent set would not give us that
  private val bookKeepingLock = new ReentrantLock()

  // condition used for awaiting termination
  private val terminationCondition = bookKeepingLock.newCondition()

  // indicates the current state of the pool
  @volatile private var poolState: PoolState = Running

  private val workQueues = Array.fill(parallelism)(new BoundedTaskQueue(affinityGroupSize))
  private val workers = mutable.Set[ThreadPoolWorker]()

  //fires up initial workers
  locked(bookKeepingLock) {
    workQueues.foreach(q ⇒ addWorker(workers, q))
  }

  private def addWorker(workers: java.util.Set[ThreadPoolWorker], q: BoundedTaskQueue): Unit = {
    locked(bookKeepingLock) {
      val worker = new ThreadPoolWorker(q)
      workers.add(worker)
      worker.startWorker()
    }
  }

  private def tryEnqueue(command: Runnable) = {
    // does a mod on the hash code to determine which queue to put task in
    val queueIndex = Math.abs(command.hashCode()) & (parallelism - 1)
    workQueues(queueIndex).add(command)
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
  private def onWorkerExit(w: ThreadPoolWorker, abruptTermination: Boolean): Unit =
    locked(bookKeepingLock) {
      workers.remove(w)
      if (workers.isEmpty && !abruptTermination && poolState >= ShuttingDown) {
        poolState = ShutDown // transition to shutdown and try to transition to termination
        attemptPoolTermination()
      }
      if (abruptTermination && poolState == Running)
        addWorker(workers, w.q)
    }

  override def execute(command: Runnable): Unit = {
    if (command == null)
      throw new NullPointerException
    if (!(poolState == Running && tryEnqueue(command)))
      reject(command)
  }

  private def reject(command: Runnable) = throw new RejectedExecutionException("Task " + command.toString + " rejected from " + this.toString)

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {

    // recurse until pool is terminated or time out reached
    @tailrec
    def awaitTermination(nanos: Long): Boolean = poolState match {
      case Terminated ⇒ true
      case _ ⇒
        if (nanos <= 0) false
        else
          awaitTermination(terminationCondition.awaitNanos(nanos))
    }

    locked(bookKeepingLock) {
      // need to hold the lock to avoid monitor exception
      awaitTermination(unit.toNanos(timeout))
    }

  }

  private def attemptPoolTermination() =
    locked(bookKeepingLock) {
      if (workers.isEmpty && poolState == ShutDown) {
        poolState = Terminated
        terminationCondition.signalAll()
      }
    }

  override def shutdownNow(): util.List[Runnable] =
    locked(bookKeepingLock) {
      poolState = ShutDown
      workers.foreach(_.stop())
      attemptPoolTermination()
      Collections.emptyList[Runnable]() // like in the FJ executor, we do not provide facility to obtain tasks that were in queue
    }

  override def shutdown(): Unit =
    locked(bookKeepingLock) {
      poolState = ShuttingDown
      // interrupts only idle workers.. so others can process their queues
      workers.foreach(_.stopIfIdle())
      attemptPoolTermination()
    }

  override def isShutdown: Boolean = poolState == ShutDown

  override def isTerminated: Boolean = poolState == Terminated

  private class ThreadPoolWorker(val q: BoundedTaskQueue) extends Runnable {

    sealed trait WorkerState
    case object NotStarted extends WorkerState
    case object InExecution extends WorkerState
    case object Idle extends WorkerState

    val thread: Thread = tf.newThread(this)
    @volatile var workerState: WorkerState = NotStarted

    def startWorker() = {
      workerState = Idle
      thread.start()
    }

    def runCommand(command: Runnable) = {
      workerState = InExecution
      try
        command.run()
      finally
        workerState = Idle
    }

    override def run(): Unit = {

      /**
       * Determines whether the worker can keep running or not.
       * In order to continue polling for tasks three conditions
       * need to be satisfied:
       *
       * 1) pool state is less than Shutting down or queue
       * is not empty (e.g pool state is ShuttingDown but there are still messages to process)
       *
       * 2) the thread backing up  this worker has not been interrupted
       *
       * 3) We are not in ShutDown state (in which we should not be processing any enqueued tasks)
       */
      def shouldKeepRunning =
        (poolState < ShuttingDown || !q.isEmpty) &&
          !Thread.interrupted() &&
          poolState != ShutDown

      var abruptTermination = true
      try {
        while (shouldKeepRunning) {
          val c = q.poll()
          if (c != null)
            runCommand(c)
          else // if not wait for a bit
            waitingStrategy()
        }
        abruptTermination = false // if we have reached here, our termination is not due to an exception
      } finally {
        onWorkerExit(this, abruptTermination)
      }
    }

    def stop() =
      if (!thread.isInterrupted && workerState != NotStarted)
        thread.interrupt()

    def stopIfIdle() =
      if (workerState == Idle)
        stop()
  }

}

object AffinityPool {

  type WaitingStrategy = () ⇒ Unit
  val busySpinWaitingStrategy = () ⇒ ()
  val sleepWaitingStrategy = () ⇒ LockSupport.parkNanos(1l)
  val yieldWaitingStrategy = () ⇒ Thread.`yield`()

  def locked[T](l: Lock)(body: ⇒ T) = {
    l.lock()
    try {
      body
    } finally {
      l.unlock()
    }
  }

  sealed trait PoolState extends Ordered[PoolState] {
    def order: Int
    override def compare(that: PoolState): Int = this.order compareTo that.order
  }

  // accepts new tasks and processes tasks that are enqueued
  case object Running extends PoolState {
    override val order: Int = 0
  }

  // does not accept new tasks, processes tasks that are in the queue
  case object ShuttingDown extends PoolState {
    override def order: Int = 1
  }

  // does not accept new tasks, does not process tasks in queue
  case object ShutDown extends PoolState {
    override def order: Int = 2
  }

  // all threads have been stopped, does not process tasks and does not accept new ones
  case object Terminated extends PoolState {
    override def order: Int = 3
  }
}

class BoundedTaskQueue(capacity: Int) extends AbstractBoundedNodeQueue[Runnable](capacity)

class AffinityPoolConfigurator(config: Config, prerequisites: DispatcherPrerequisites) extends ExecutorServiceConfigurator(config, prerequisites) {

  sealed trait CPUAffinityStrategy {
    def javaStrat: AffinityStrategies
  }

  case object AnyStrat extends CPUAffinityStrategy {
    override val javaStrat: AffinityStrategies = AffinityStrategies.ANY
  }
  case object SameCore extends CPUAffinityStrategy {
    override val javaStrat: AffinityStrategies = AffinityStrategies.SAME_CORE
  }
  case object SameSocket extends CPUAffinityStrategy {
    override val javaStrat: AffinityStrategies = AffinityStrategies.SAME_SOCKET
  }
  case object DifferentCore extends CPUAffinityStrategy {
    override val javaStrat: AffinityStrategies = AffinityStrategies.DIFFERENT_CORE
  }
  case object DifferentSocket extends CPUAffinityStrategy {
    override val javaStrat: AffinityStrategies = AffinityStrategies.DIFFERENT_SOCKET
  }

  private def toAffinityStrategy(s: String): CPUAffinityStrategy = s match {
    case "any"              ⇒ AnyStrat
    case "same-core"        ⇒ SameCore
    case "same-socket"      ⇒ SameSocket
    case "different-core"   ⇒ DifferentCore
    case "different-socket" ⇒ DifferentSocket
    case x                  ⇒ throw new IllegalArgumentException("[%s] is not a valid cpu affinity strategy [any, same-core, same-socket, different-core, different-socket]!" format x)

  }

  private def toWaitingStrategy(s: String): WaitingStrategy = s match {
    case "sleep"     ⇒ sleepWaitingStrategy
    case "yield"     ⇒ yieldWaitingStrategy
    case "busy-spin" ⇒ busySpinWaitingStrategy
    case x           ⇒ throw new IllegalArgumentException("[%s] is not a valid waiting strategy[sleep, yield, same-socket, busy-spin]!" format x)

  }

  private val poolSize = ThreadPoolConfig.scaledPoolSize(
    config.getInt("parallelism-min"),
    config.getDouble("parallelism-factor"),
    config.getInt("parallelism-max"))
  private val affinityGroupSize = config.getInt("affinity-group-size")
  private val strategies = config.getStringList("cpu-affinity-strategies").map(toAffinityStrategy)
  private val waitingStrat = toWaitingStrategy(config.getString("worker-waiting-strategy"))
  private val tf = new AffinityThreadFactory("affinity-thread-fact", strategies.map(_.javaStrat): _*)

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = new ExecutorServiceFactory {
    override def createExecutorService: ExecutorService = new AffinityPool(poolSize, affinityGroupSize, tf, waitingStrat)
  }
}

object AffinityPoolConfigurator {

  def main(args: Array[String]): Unit = {
    val pool = new AffinityPool(1, 10000, MonitorableThreadFactory("tf", daemonic = false, None, MonitorableThreadFactory.doNothing), sleepWaitingStrategy)
    //val pool = new ThreadPoolExecutor(8,8,8,TimeUnit.MINUTES,new LinkedBlockingQueue[Runnable]())
    //(8,10000, MonitorableThreadFactory("tf", daemonic = false, None, MonitorableThreadFactory.doNothing))

    for {
      x ← 1 to 100
    } yield pool.submit(new Runnable {
      override def run(): Unit = {
        println("Started with " + x)
        Thread.sleep(3000)
        println("Done with " + x)
      }
    })

    println("awaiting termination")
    Thread.sleep(50)
    pool.shutdown
    pool.awaitTermination(100, TimeUnit.HOURS)
    println("awaiting termination done")

  }
}

