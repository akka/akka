/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util.concurrent.{ CompletionStage, ThreadFactory }

import scala.collection.immutable.SortedMap
import scala.compat.java8.FutureConverters
import scala.concurrent._

import scala.annotation.nowarn
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import akka.{ actor => classic }
import akka.Done
import akka.actor.{ ActorPath, ActorRefProvider, Address, Cancellable, ReflectiveDynamicAccess }
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.Dispatchers
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.Props
import akka.actor.typed.Scheduler
import akka.actor.typed.Settings
import akka.actor.typed.internal.ActorRefImpl
import akka.actor.typed.internal.InternalRecipientRef
import akka.actor.typed.receptionist.Receptionist
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@nowarn
@InternalApi private[akka] final class ActorSystemStub(val name: String)
    extends ActorSystem[Nothing]
    with ActorRef[Nothing]
    with ActorRefImpl[Nothing]
    with InternalRecipientRef[Nothing] {

  private val rootPath: ActorPath = classic.RootActorPath(classic.Address("akka", name))

  override val path: classic.ActorPath = rootPath / "user"

  override val settings: Settings = {
    val classLoader = getClass.getClassLoader
    val dynamicAccess = new ReflectiveDynamicAccess(classLoader)
    val config =
      classic.ActorSystem.Settings.amendSlf4jConfig(ConfigFactory.defaultReference(classLoader), dynamicAccess)
    val untypedSettings = new classic.ActorSystem.Settings(classLoader, config, name)
    new Settings(untypedSettings)
  }

  override def tell(message: Nothing): Unit =
    throw new UnsupportedOperationException("must not send message to ActorSystemStub")

  // impl ActorRefImpl
  override def isLocal: Boolean = true
  // impl ActorRefImpl
  override def sendSystem(signal: akka.actor.typed.internal.SystemMessage): Unit =
    throw new UnsupportedOperationException("must not send SYSTEM message to ActorSystemStub")

  // impl InternalRecipientRef, ask not supported
  override def provider: ActorRefProvider = throw new UnsupportedOperationException("no provider")

  // stream materialization etc. using stub not supported
  override def classicSystem =
    throw new UnsupportedOperationException("no classic actor system available")

  // impl InternalRecipientRef
  def isTerminated: Boolean = whenTerminated.isCompleted

  val deadLettersInbox = new DebugRef[Any](path.parent / "deadLetters", true)
  override def deadLetters[U]: ActorRef[U] = deadLettersInbox

  override def ignoreRef[U]: ActorRef[U] = deadLettersInbox

  val receptionistInbox = new TestInboxImpl[Receptionist.Command](path.parent / "receptionist")

  override def receptionist: ActorRef[Receptionist.Command] = receptionistInbox.ref

  val controlledExecutor = new ControlledExecutor
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = controlledExecutor
  override def dispatchers: akka.actor.typed.Dispatchers = new Dispatchers {
    def lookup(selector: DispatcherSelector): ExecutionContextExecutor = controlledExecutor
    def shutdown(): Unit = ()
  }

  override def dynamicAccess: classic.DynamicAccess = new classic.ReflectiveDynamicAccess(getClass.getClassLoader)

  override def logConfiguration(): Unit = log.info(settings.toString)

  override def scheduler: ActorSystemStub.SynchronousScheduler =
    new ActorSystemStub.SynchronousScheduler()

  private val terminationPromise = Promise[Done]()
  override def terminate(): Unit = terminationPromise.trySuccess(Done)
  override def whenTerminated: Future[Done] = terminationPromise.future
  override def getWhenTerminated: CompletionStage[Done] = FutureConverters.toJava(whenTerminated)
  override val startTime: Long = System.currentTimeMillis()
  override def uptime: Long = System.currentTimeMillis() - startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = new ThreadFactory {
    override def newThread(r: Runnable): Thread = new Thread(r)
  }

  override def printTree: String = "no tree for ActorSystemStub"

  override def systemActorOf[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U] = {
    throw new UnsupportedOperationException("ActorSystemStub cannot create system actors")
  }

  override def registerExtension[T <: Extension](ext: ExtensionId[T]): T =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  override def extension[T <: Extension](ext: ExtensionId[T]): T =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  override def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean =
    throw new UnsupportedOperationException("ActorSystemStub cannot register extensions")

  override def log: Logger = LoggerFactory.getLogger(getClass)

  def address: Address = rootPath.address
}

object ActorSystemStub {
  // Should only be used in synchronous tests, thus all the synchronization... Note that passed executors are not used: all runnables are run
  // as part of 'advanceTimeBy'
  final class SynchronousScheduler private[ActorSystemStub]() extends Scheduler {
    import duration.FiniteDuration
    import akka.util.JavaDurationConverters

    def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit executor: ExecutionContext): Cancellable = {
      val runAfter = delay.toNanos + currentTimeNanos
      val ret = cancellable()

      synchronized {
        if (runAfter < currentTimeNanos) {
          throw new IllegalArgumentException(s"$delay causes arithmetic overflow")
        }

        tracker = tracker.updated(ret, runAfter, runnable)
      }

      ret
    }

    def scheduleOnce(delay: java.time.Duration, runnable: Runnable, executor: ExecutionContext): Cancellable = {
      import JavaDurationConverters._

      scheduleOnce(delay.asScala, runnable)(executor)
    }

    def scheduleWithFixedDelay(initialDelay: FiniteDuration, delay: FiniteDuration)(runnable: Runnable)(
      implicit executor: ExecutionContext
    ): Cancellable = {
      val wrappedRunnable: Runnable = () => {
        runnable.run()
        scheduleWithFixedDelay(delay, delay)(runnable)
      }
      scheduleOnce(initialDelay, wrappedRunnable)
    }

    def scheduleWithFixedDelay(
      initialDelay: java.time.Duration,
      delay: java.time.Duration,
      runnable: Runnable,
      executor: ExecutionContext
    ): Cancellable = {
      import JavaDurationConverters._

      scheduleWithFixedDelay(initialDelay.asScala, delay.asScala)(runnable)(executor)
    }

    // NB: due to the artificiality of this scheduler, this is the same as 'scheduleWithFixedDelay'
    def scheduleAtFixedRate(initialDelay: FiniteDuration, delay: FiniteDuration)(runnable: Runnable)(
      implicit executor: ExecutionContext
    ): Cancellable =
      scheduleWithFixedDelay(initialDelay, delay)(runnable)

    def scheduleAtFixedRate(
      initialDelay: java.time.Duration,
      delay: java.time.Duration,
      runnable: Runnable,
      executor: ExecutionContext
    ): Cancellable =
      scheduleWithFixedDelay(initialDelay, delay, runnable, executor)

    def advanceTimeBy(duration: FiniteDuration): Unit = {
      val tasks =
        synchronized {
          val nextTime = currentTimeNanos + duration.toNanos
          if (nextTime < currentTimeNanos) {
            throw new ArithmeticException(s"$duration causes arithmetic overflow")
          } else {
            currentTimeNanos = nextTime
          }
          val (tasks, nextTracker) = tracker.toPerformThrough(nextTime)
          tracker = nextTracker
          tasks
        }
      tasks.foreach {
        case (c, r) =>
          c.cancel()
          r.run()
      }
    }

    private[this] def cancellable(): Cancellable = {
      val outer = this

      new Cancellable {
        def cancel(): Boolean =
          outer.synchronized {
            if (_cancelled) false
            else {
              tracker = tracker.without(this)
              _cancelled = true
              true
            }
          }

        def isCancelled: Boolean = _cancelled
        private[this] var _cancelled: Boolean = false
      }
    }

    private[this] var tracker: TaskTracker = TaskTracker.Empty
    private[this] var currentTimeNanos: Long = Long.MinValue
  }

  final class TaskTracker private[TaskTracker](
    val byKey: Map[Cancellable, (Long, Runnable)],
    val preSorted: SortedMap[Long, Map[Cancellable, Runnable]]
  ) {
    def size: Int = byKey.size

    def toPerformThrough(time: Long): (Iterable[(Cancellable, Runnable)], TaskTracker) = {
      val ranged = preSorted.range(Long.MinValue, time) ++ preSorted.get(time).map(time -> _)
      val afterByKey = byKey.filter {
        case (_, (t, _)) => t > time
      }
      val afterSorted = preSorted.rangeImpl(Some(time), None) - time

      ranged.values.flatten -> new TaskTracker(afterByKey, afterSorted)
    }

    def updated(c: Cancellable, t: Long, r: Runnable): TaskTracker = {
      val presortedWithout = preSortedWithout(c)

      val nextPresorted =
        presortedWithout.get(t)
          .map(m => presortedWithout.updated(t, m.updated(c, r)))
          .getOrElse(presortedWithout + (t -> Map(c -> r)))

      val nextByKey = byKey.updated(c, t -> r)
      new TaskTracker(nextByKey, nextPresorted)
    }

    def without(c: Cancellable): TaskTracker = {
      val nextByKey = byKey - c
      new TaskTracker(nextByKey, preSortedWithout(c))
    }

    override def toString: String = byKey.toString

    private[this] def preSortedWithout(c: Cancellable): SortedMap[Long, Map[Cancellable, Runnable]] =
      byKey.get(c)
        .map {
          case (t, _) =>
            val nextTasks = preSorted.get(t).map(_ - c).getOrElse(Map.empty)

            if (nextTasks.isEmpty) (preSorted - t)
            else preSorted.updated(t, nextTasks)
        }
        .getOrElse(preSorted)
  }

  object TaskTracker {
    def apply(byKey: Map[Cancellable, (Long, Runnable)]): TaskTracker = {
      var toBuild: SortedMap[Long, Map[Cancellable, Runnable]] = SortedMap.empty

      // Compatible with Scalas 2.12 and 2.13...
      byKey.groupBy(_._2._1).mapValues(_.mapValues(_._2)).foreach {
        case (t, m) =>
          toBuild = toBuild + (t -> m)
      }
      val preSorted = toBuild

      new TaskTracker(byKey, preSorted)
    }

    val Empty: TaskTracker = new TaskTracker(Map.empty, SortedMap.empty)
  }
}
