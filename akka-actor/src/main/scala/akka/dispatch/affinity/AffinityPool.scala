/**
 *  Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.dispatch.affinity

import java.util
import java.util.concurrent._
import java.util.concurrent.locks.{Lock, ReentrantLock}

import akka.dispatch._
import akka.dispatch.affinity.AffinityPool._
import com.typesafe.config.Config
import net.openhft.affinity.{AffinityStrategies, AffinityThreadFactory}

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.util.{Failure, Try}

class AffinityPool(parallelism: Int, affinityGroupSize: Int, tf: ThreadFactory) extends AbstractExecutorService {

  if (parallelism <= 0)
    throw new IllegalArgumentException("Size of pool cannot be less or equal to 0")

  // controls access to mutable such as list of workers, etc
  private val mainLock = new ReentrantLock
  @volatile private var poolState: PoolState = Running

  // there is one queue for each thread
  private val workQueues = Array.fill(parallelism)(new BoundedTaskQueue(affinityGroupSize))

  //mutable set of all workers that are currently running
  private val workers: mutable.Set[ThreadPoolWorker] = mutable.Set()

  //fires up initial workers
  workQueues.foreach(q ⇒ addWorker(workers, q))

  private def addWorker(workers: mutable.Set[ThreadPoolWorker], q: BoundedTaskQueue): Unit = {
    locked(mainLock) {
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

  private def onWorkerExit(w: ThreadPoolWorker): Unit = {
    locked(mainLock) {
      workers.remove(w)
    }
  }

  private def onWorkerFailure(w: ThreadPoolWorker): Unit = {
    locked(mainLock) {
      workers.remove(w)
      addWorker(workers, w.q)
    }
  }

  private class ThreadPoolWorker(val q: BoundedTaskQueue) extends Runnable {

    sealed trait WorkerState
    case object NotStarted extends WorkerState
    case object InExecution extends WorkerState
    case object Idle extends WorkerState

    private val thread = tf.newThread(this)
    @volatile var workerState: WorkerState = NotStarted

    def startWorker() = thread.start()

    def runCommand(command: Runnable) = {
      workerState = InExecution
      try
        command.run()
      catch {
        case x: RuntimeException ⇒ throw x
        case e: Error            ⇒ throw e
        case t: Throwable        ⇒ throw new Error(t)
      } finally {
        workerState = Idle
      }
    }

    override def run(): Unit =
      Try {
        workerState = Idle
        while (poolState == Running || !q.isEmpty) {
          val c = q.poll()
          if (c != null) runCommand(c)
        }
      } match {
        case Failure(_) ⇒ onWorkerFailure(this)
        case _          ⇒ onWorkerExit(this)
      }

    def interruptIfStarted() =
      if (workerState != NotStarted && !thread.isInterrupted)
        thread.interrupt()

    def interruptIfIdle() =
      if (workerState == Idle && !thread.isInterrupted)
        thread.interrupt()

  }

  override def execute(command: Runnable): Unit = {
    if (command == null)
      throw new NullPointerException
    if (!(poolState == Running && tryEnqueue(command)))
      reject(command)
  }

  private def reject(command: Runnable) = throw new RejectedExecutionException("Task " + command.toString + " rejected from " + this.toString)

  //TODO: Implement via wait condition
  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

  // attempts to transition pool into Terminated state
  // TODO: need to implement this so we are sure all workers are gone and there are no more tasks left in the queue
  private def attemptPoolTermination() = poolState = Terminated

  override def shutdownNow(): util.List[Runnable] = {
    poolState = ShuttingDown
    //interrupts all workers
    locked(mainLock) {
      workers.foreach(_.interruptIfStarted())
    }
    poolState = ShutDown
    seqAsJavaList(workQueues.flatMap(_.drain))
  }

  override def shutdown(): Unit = {
    poolState = ShuttingDown
    // interrupts only idle workers.. so others can process their queues
    locked(mainLock) {
      workers.foreach(_.interruptIfIdle())
    }
    attemptPoolTermination()
  }

  override def isShutdown: Boolean = poolState == ShutDown

  override def isTerminated: Boolean = poolState == Terminated

}

object AffinityPool {
  def locked[T](l: Lock)(body: ⇒ T) = {
    l.lock()
    try {
      body
    } finally {
      l.unlock()
    }
  }

  sealed trait PoolState
  case object Running extends PoolState // accepts new tasks and processes tasks that are enqueued
  case object ShuttingDown extends PoolState // does not accept new tasks, processes tasks that are in the queue
  case object ShutDown extends PoolState // does not accept new tasks, does not process tasks in queue
  case object Terminated extends PoolState // all threads have been stopped, does not process tasks and does not accept new ones
}

class BoundedTaskQueue(capacity: Int) extends AbstractBoundedNodeQueue[Runnable](capacity) {
  def drain: Seq[Runnable] = {
    val buffer = scala.collection.mutable.ListBuffer.empty[Runnable]
    while (!this.isEmpty)
      buffer append this.poll()
    buffer.toList
  }

}

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

  def toStrategy(s: String): CPUAffinityStrategy = s match {
    case "any"              ⇒ AnyStrat
    case "same-core"        ⇒ SameCore
    case "same-socket"      ⇒ SameSocket
    case "different-core"   ⇒ DifferentCore
    case "different-socket" ⇒ DifferentSocket
    case x                  ⇒ throw new IllegalArgumentException("[%s] is not a valid cpu affinity strategy [any, same-core, same-socket, different-core, different-socket]!" format x)

  }

  def getNonNegative[T: Numeric](getter: String => T, key: String): Unit = {

  }

  val poolSize =  ThreadPoolConfig.scaledPoolSize(
    config.getInt("parallelism-min"),
    config.getDouble("parallelism-factor"),
    config.getInt("parallelism-max"))
  val affinityGroupSize = config.getInt("affinity-group-size")
  val strategies = config.getStringList("cpu-affinity-strategies").map(toStrategy)
  val threadFactory = new AffinityThreadFactory("affinity-thread-fact", strategies.map(_.javaStrat): _*)

  override def createExecutorServiceFactory(id: String, threadFactory: ThreadFactory): ExecutorServiceFactory = new ExecutorServiceFactory {
    override def createExecutorService: ExecutorService = new AffinityPool(poolSize, affinityGroupSize, threadFactory)
  }
}

