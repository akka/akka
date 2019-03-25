/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import scala.concurrent.duration._
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._
import java.util.concurrent._
import java.util.concurrent.TimeUnit.MILLISECONDS

import scala.concurrent.Future
import scala.concurrent.Promise
import akka.Done
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.annotation.tailrec
import com.typesafe.config.ConfigFactory
import akka.pattern.after

import scala.util.control.NonFatal
import akka.event.Logging
import akka.dispatch.ExecutionContexts

import scala.util.Try
import scala.concurrent.Await
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import java.util.Optional

import akka.annotation.InternalApi
import akka.util.OptionVal

object CoordinatedShutdown extends ExtensionId[CoordinatedShutdown] with ExtensionIdProvider {

  /**
   * The first pre-defined phase that applications can add tasks to.
   * Note that more phases can be added in the application's
   * configuration by overriding this phase with an additional
   * depends-on.
   */
  val PhaseBeforeServiceUnbind = "before-service-unbind"

  /**
   * Stop accepting new incoming requests in for example HTTP.
   */
  val PhaseServiceUnbind = "service-unbind"

  /**
   * Wait for requests that are in progress to be completed.
   */
  val PhaseServiceRequestsDone = "service-requests-done"

  /**
   * Final shutdown of service endpoints.
   */
  val PhaseServiceStop = "service-stop"

  /**
   * Phase for custom application tasks that are to be run
   * after service shutdown and before cluster shutdown.
   */
  val PhaseBeforeClusterShutdown = "before-cluster-shutdown"

  /**
   * Graceful shutdown of the Cluster Sharding regions.
   */
  val PhaseClusterShardingShutdownRegion = "cluster-sharding-shutdown-region"

  /**
   * Emit the leave command for the node that is shutting down.
   */
  val PhaseClusterLeave = "cluster-leave"

  /**
   * Shutdown cluster singletons
   */
  val PhaseClusterExiting = "cluster-exiting"

  /**
   * Wait until exiting has been completed
   */
  val PhaseClusterExitingDone = "cluster-exiting-done"

  /**
   * Shutdown the cluster extension
   */
  val PhaseClusterShutdown = "cluster-shutdown"

  /**
   * Phase for custom application tasks that are to be run
   * after cluster shutdown and before ActorSystem termination.
   */
  val PhaseBeforeActorSystemTerminate = "before-actor-system-terminate"

  /**
   * Last phase. See terminate-actor-system and exit-jvm above.
   * Don't add phases that depends on this phase because the
   * dispatcher and scheduler of the ActorSystem have been shutdown.
   */
  val PhaseActorSystemTerminate = "actor-system-terminate"

  /**
   * Reason for the shutdown, which can be used by tasks in case they need to do
   * different things depending on what caused the shutdown. There are some
   * predefined reasons, but external libraries applications may also define
   * other reasons.
   */
  trait Reason

  /**
   * Scala API: The reason for the shutdown was unknown. Needed for backwards compatibility.
   */
  case object UnknownReason extends Reason

  /**
   * Java API: The reason for the shutdown was unknown. Needed for backwards compatibility.
   */
  def unknownReason: Reason = UnknownReason

  /**
   * Scala API: The shutdown was initiated by a JVM shutdown hook, e.g. triggered by SIGTERM.
   */
  object JvmExitReason extends Reason

  /**
   * Java API: The shutdown was initiated by a JVM shutdown hook, e.g. triggered by SIGTERM.
   */
  def jvmExitReason: Reason = JvmExitReason

  /**
   * Scala API: The shutdown was initiated by Cluster downing.
   */
  object ClusterDowningReason extends Reason

  /**
   * Java API: The shutdown was initiated by Cluster downing.
   */
  def clusterDowningReason: Reason = ClusterDowningReason

  /**
   * Scala API: The shutdown was initiated by Cluster leaving.
   */
  object ClusterLeavingReason extends Reason

  /**
   * Java API: The shutdown was initiated by Cluster leaving.
   */
  def clusterLeavingReason: Reason = ClusterLeavingReason

  @volatile private var runningJvmHook = false

  override def get(system: ActorSystem): CoordinatedShutdown = super.get(system)

  override def lookup = CoordinatedShutdown

  override def createExtension(system: ExtendedActorSystem): CoordinatedShutdown = {
    val conf = system.settings.config.getConfig("akka.coordinated-shutdown")
    val phases = phasesFromConfig(conf)
    val coord = new CoordinatedShutdown(system, phases)
    initPhaseActorSystemTerminate(system, conf, coord)
    initJvmHook(system, conf, coord)
    // Avoid leaking actor system references when system is terminated before JVM is #23384
    // Catching RejectedExecutionException in case extension is accessed first time when
    // system is already terminated, see #25592. The extension is eagerly loaded when ActorSystem
    // is started but it might be a race between (failing?) startup and shutdown.
    def cleanupActorSystemJvmHook(): Unit = {
      coord.actorSystemJvmHook match {
        case OptionVal.Some(cancellable) if !runningJvmHook && !cancellable.isCancelled =>
          cancellable.cancel()
          coord.actorSystemJvmHook = OptionVal.None
        case _ =>
      }
    }
    try system.registerOnTermination(cleanupActorSystemJvmHook())
    catch {
      case _: RejectedExecutionException => cleanupActorSystemJvmHook()
    }
    coord
  }

  // locate reason-specific overrides and merge with defaults.
  @InternalApi private[akka] def confWithOverrides(conf: Config, reason: Option[Reason]): Config = {
    reason
      .flatMap { r =>
        val basePath = s"""reason-overrides."${r.getClass.getName}""""
        if (conf.hasPath(basePath)) Some(conf.getConfig(basePath).withFallback(conf)) else None
      }
      .getOrElse(conf)
  }

  private def initPhaseActorSystemTerminate(system: ActorSystem, conf: Config, coord: CoordinatedShutdown): Unit = {
    coord.addTask(PhaseActorSystemTerminate, "terminate-system") { () =>
      val confForReason = confWithOverrides(conf, coord.shutdownReason())
      val terminateActorSystem = confForReason.getBoolean("terminate-actor-system")
      val exitJvm = confForReason.getBoolean("exit-jvm")
      val exitCode = confForReason.getInt("exit-code")

      if (exitJvm && terminateActorSystem) {
        // In case ActorSystem shutdown takes longer than the phase timeout,
        // exit the JVM forcefully anyway.
        // We must spawn a separate thread to not block current thread,
        // since that would have blocked the shutdown of the ActorSystem.
        val timeout = coord.timeout(PhaseActorSystemTerminate)
        val t = new Thread {
          override def run(): Unit = {
            if (Try(Await.ready(system.whenTerminated, timeout)).isFailure && !runningJvmHook)
              System.exit(exitCode)
          }
        }
        t.setName("CoordinatedShutdown-exit")
        t.start()
      }

      if (terminateActorSystem) {
        system
          .terminate()
          .map { _ =>
            if (exitJvm && !runningJvmHook) System.exit(exitCode)
            Done
          }(ExecutionContexts.sameThreadExecutionContext)
      } else if (exitJvm) {
        System.exit(exitCode)
        Future.successful(Done)
      } else
        Future.successful(Done)
    }
  }

  private def initJvmHook(system: ActorSystem, conf: Config, coord: CoordinatedShutdown): Unit = {
    val runByJvmShutdownHook = system.settings.JvmShutdownHooks && conf.getBoolean("run-by-jvm-shutdown-hook")
    if (runByJvmShutdownHook) {
      coord.actorSystemJvmHook = OptionVal.Some(coord.addCancellableJvmShutdownHook {
        runningJvmHook = true // avoid System.exit from PhaseActorSystemTerminate task
        if (!system.whenTerminated.isCompleted) {
          coord.log.debug("Starting coordinated shutdown from JVM shutdown hook")
          try {
            // totalTimeout will be 0 when no tasks registered, so at least 3.seconds
            val totalTimeout = coord.totalTimeout().max(3.seconds)
            Await.ready(coord.run(JvmExitReason), totalTimeout)
          } catch {
            case NonFatal(e) =>
              coord.log.warning("CoordinatedShutdown from JVM shutdown failed: {}", e.getMessage)
          }
        }
      })
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] final case class Phase(
      dependsOn: Set[String],
      timeout: FiniteDuration,
      recover: Boolean,
      enabled: Boolean)

  /**
   * INTERNAL API
   */
  private[akka] def phasesFromConfig(conf: Config): Map[String, Phase] = {
    import scala.collection.JavaConverters._
    val defaultPhaseTimeout = conf.getString("default-phase-timeout")
    val phasesConf = conf.getConfig("phases")
    val defaultPhaseConfig = ConfigFactory.parseString(s"""
      timeout = $defaultPhaseTimeout
      recover = true
      enabled = true
      depends-on = []
    """)
    phasesConf.root.unwrapped.asScala.toMap.map {
      case (k, _: java.util.Map[_, _]) =>
        val c = phasesConf.getConfig(k).withFallback(defaultPhaseConfig)
        val dependsOn = c.getStringList("depends-on").asScala.toSet
        val timeout = c.getDuration("timeout", MILLISECONDS).millis
        val recover = c.getBoolean("recover")
        val enabled = c.getBoolean("enabled")
        k -> Phase(dependsOn, timeout, recover, enabled)
      case (k, v) =>
        throw new IllegalArgumentException(s"Expected object value for [$k], got [$v]")
    }
  }

  /**
   * INTERNAL API: https://en.wikipedia.org/wiki/Topological_sorting
   */
  private[akka] def topologicalSort(phases: Map[String, Phase]): List[String] = {
    var result = List.empty[String]
    var unmarked = phases.keySet ++ phases.values.flatMap(_.dependsOn) // in case phase is not defined as key
    var tempMark = Set.empty[String] // for detecting cycles

    while (unmarked.nonEmpty) {
      depthFirstSearch(unmarked.head)
    }

    def depthFirstSearch(u: String): Unit = {
      if (tempMark(u))
        throw new IllegalArgumentException(
          "Cycle detected in graph of phases. It must be a DAG. " +
          s"phase [$u] depends transitively on itself. All dependencies: $phases")
      if (unmarked(u)) {
        tempMark += u
        phases.get(u) match {
          case Some(p) => p.dependsOn.foreach(depthFirstSearch)
          case None    =>
        }
        unmarked -= u // permanent mark
        tempMark -= u
        result = u :: result
      }
    }

    result.reverse
  }

}

final class CoordinatedShutdown private[akka] (
    system: ExtendedActorSystem,
    phases: Map[String, CoordinatedShutdown.Phase])
    extends Extension {
  import CoordinatedShutdown.Reason
  import CoordinatedShutdown.UnknownReason

  /** INTERNAL API */
  private[akka] val log = Logging(system, getClass)
  private val knownPhases = phases.keySet ++ phases.values.flatMap(_.dependsOn)

  /** INTERNAL API */
  private[akka] val orderedPhases = CoordinatedShutdown.topologicalSort(phases)
  private val tasks = new ConcurrentHashMap[String, Vector[(String, () => Future[Done])]]
  private val runStarted = new AtomicReference[Option[Reason]](None)
  private val runPromise = Promise[Done]()

  private val _jvmHooksLatch = new AtomicReference[CountDownLatch](new CountDownLatch(0))
  @volatile private var actorSystemJvmHook: OptionVal[Cancellable] = OptionVal.None

  /**
   * INTERNAL API
   */
  private[akka] def jvmHooksLatch: CountDownLatch = _jvmHooksLatch.get

  /**
   * Scala API: Add a task to a phase. It doesn't remove previously added tasks.
   * Tasks added to the same phase are executed in parallel without any
   * ordering assumptions. Next phase will not start until all tasks of
   * previous phase have been completed.
   *
   * Tasks should typically be registered as early as possible after system
   * startup. When running the coordinated shutdown tasks that have been registered
   * will be performed but tasks that are added too late will not be run.
   * It is possible to add a task to a later phase by a task in an earlier phase
   * and it will be performed.
   */
  @tailrec def addTask(phase: String, taskName: String)(task: () => Future[Done]): Unit = {
    require(
      knownPhases(phase),
      s"Unknown phase [$phase], known phases [$knownPhases]. " +
      "All phases (along with their optional dependencies) must be defined in configuration")
    require(
      taskName.nonEmpty,
      "Set a task name when adding tasks to the Coordinated Shutdown. " +
      "Try to use unique, self-explanatory names.")
    val current = tasks.get(phase)
    if (current == null) {
      if (tasks.putIfAbsent(phase, Vector(taskName -> task)) != null)
        addTask(phase, taskName)(task) // CAS failed, retry
    } else {
      if (!tasks.replace(phase, current, current :+ (taskName -> task)))
        addTask(phase, taskName)(task) // CAS failed, retry
    }
  }

  /**
   * Java API: Add a task to a phase. It doesn't remove previously added tasks.
   * Tasks added to the same phase are executed in parallel without any
   * ordering assumptions. Next phase will not start until all tasks of
   * previous phase have been completed.
   *
   * Tasks should typically be registered as early as possible after system
   * startup. When running the coordinated shutdown tasks that have been registered
   * will be performed but tasks that are added too late will not be run.
   * It is possible to add a task to a later phase by a task in an earlier phase
   * and it will be performed.
   */
  def addTask(phase: String, taskName: String, task: Supplier[CompletionStage[Done]]): Unit =
    addTask(phase, taskName)(() => task.get().toScala)

  /**
   * The `Reason` for the shutdown as passed to the `run` method. `None` if the shutdown
   * has not been started.
   */
  def shutdownReason(): Option[Reason] = runStarted.get()

  /**
   * The `Reason` for the shutdown as passed to the `run` method. `Optional.empty` if the shutdown
   * has not been started.
   */
  def getShutdownReason(): Optional[Reason] = shutdownReason().asJava

  /**
   * Scala API: Run tasks of all phases. The returned
   * `Future` is completed when all tasks have been completed,
   * or there is a failure when recovery is disabled.
   *
   * It's safe to call this method multiple times. It will only run the shutdown sequence once.
   */
  def run(reason: Reason): Future[Done] = run(reason, None)

  @deprecated("Use the method with `reason` parameter instead", since = "2.5.8")
  def run(): Future[Done] = run(UnknownReason)

  /**
   * Java API: Run tasks of all phases. The returned
   * `CompletionStage` is completed when all tasks have been completed,
   * or there is a failure when recovery is disabled.
   *
   * It's safe to call this method multiple times. It will only run the shutdown sequence once.
   */
  def runAll(reason: Reason): CompletionStage[Done] = run(reason).toJava

  @deprecated("Use the method with `reason` parameter instead", since = "2.5.8")
  def runAll(): CompletionStage[Done] = runAll(UnknownReason)

  /**
   * Scala API: Run tasks of all phases including and after the given phase.
   * The returned `Future` is completed when all such tasks have been completed,
   * or there is a failure when recovery is disabled.
   *
   * It's safe to call this method multiple times. It will only run shutdown sequence once.
   */
  def run(reason: Reason, fromPhase: Option[String]): Future[Done] = {
    if (runStarted.compareAndSet(None, Some(reason))) {
      import system.dispatcher
      val debugEnabled = log.isDebugEnabled
      def loop(remainingPhases: List[String]): Future[Done] = {
        remainingPhases match {
          case Nil => Future.successful(Done)
          case phase :: remaining if !phases(phase).enabled =>
            tasks.get(phase) match {
              case null => // This pretty much is ok as there are no tasks
              case tasks =>
                log.info("Phase [{}] disabled through configuration, skipping [{}] tasks", phase, tasks.size)
            }
            loop(remaining)
          case phase :: remaining =>
            val phaseResult = tasks.get(phase) match {
              case null =>
                if (debugEnabled) log.debug("Performing phase [{}] with [0] tasks", phase)
                Future.successful(Done)
              case tasks =>
                if (debugEnabled)
                  log.debug(
                    "Performing phase [{}] with [{}] tasks: [{}]",
                    phase,
                    tasks.size,
                    tasks.map { case (taskName, _) => taskName }.mkString(", "))
                // note that tasks within same phase are performed in parallel
                val recoverEnabled = phases(phase).recover
                val result = Future
                  .sequence(tasks.map {
                    case (taskName, task) =>
                      try {
                        val r = task.apply()
                        if (recoverEnabled) r.recover {
                          case NonFatal(e) =>
                            log.warning("Task [{}] failed in phase [{}]: {}", taskName, phase, e.getMessage)
                            Done
                        } else r
                      } catch {
                        case NonFatal(e) =>
                          // in case task.apply throws
                          if (recoverEnabled) {
                            log.warning("Task [{}] failed in phase [{}]: {}", taskName, phase, e.getMessage)
                            Future.successful(Done)
                          } else
                            Future.failed(e)
                      }
                  })
                  .map(_ => Done)(ExecutionContexts.sameThreadExecutionContext)
                val timeout = phases(phase).timeout
                val deadline = Deadline.now + timeout
                val timeoutFut = try {
                  after(timeout, system.scheduler) {
                    if (phase == CoordinatedShutdown.PhaseActorSystemTerminate && deadline.hasTimeLeft) {
                      // too early, i.e. triggered by system termination
                      result
                    } else if (result.isCompleted)
                      Future.successful(Done)
                    else if (recoverEnabled) {
                      log.warning("Coordinated shutdown phase [{}] timed out after {}", phase, timeout)
                      Future.successful(Done)
                    } else
                      Future.failed(
                        new TimeoutException(s"Coordinated shutdown phase [$phase] timed out after $timeout"))
                  }
                } catch {
                  case _: IllegalStateException =>
                    // The call to `after` threw IllegalStateException, triggered by system termination
                    result
                }
                Future.firstCompletedOf(List(result, timeoutFut))
            }
            if (remaining.isEmpty)
              phaseResult // avoid flatMap when system terminated in last phase
            else
              phaseResult.flatMap(_ => loop(remaining))
        }
      }

      val remainingPhases = fromPhase match {
        case None    => orderedPhases // all
        case Some(p) => orderedPhases.dropWhile(_ != p)
      }
      val done = loop(remainingPhases)
      runPromise.completeWith(done)
    }
    runPromise.future
  }

  @deprecated("Use the method with `reason` parameter instead", since = "2.5.8")
  def run(fromPhase: Option[String]): Future[Done] =
    run(UnknownReason, fromPhase)

  /**
   * Java API: Run tasks of all phases including and after the given phase.
   * The returned `CompletionStage` is completed when all such tasks have been completed,
   * or there is a failure when recovery is disabled.
   *
   * It's safe to call this method multiple times. It will only run the shutdown sequence once.
   */
  def run(reason: Reason, fromPhase: Optional[String]): CompletionStage[Done] =
    run(reason, fromPhase.asScala).toJava

  @deprecated("Use the method with `reason` parameter instead", since = "2.5.8")
  def run(fromPhase: Optional[String]): CompletionStage[Done] =
    run(UnknownReason, fromPhase)

  /**
   * The configured timeout for a given `phase`.
   * For example useful as timeout when actor `ask` requests
   * is used as a task.
   */
  def timeout(phase: String): FiniteDuration =
    phases.get(phase) match {
      case Some(p) => p.timeout
      case None =>
        throw new IllegalArgumentException(s"Unknown phase [$phase]. All phases must be defined in configuration")
    }

  /**
   * Sum of timeouts of all phases that have some task.
   */
  def totalTimeout(): FiniteDuration = {
    import scala.collection.JavaConverters._
    tasks.keySet.asScala.foldLeft(Duration.Zero) {
      case (acc, phase) => acc + timeout(phase)
    }
  }

  /**
   * Scala API: Add a JVM shutdown hook that will be run when the JVM process
   * begins its shutdown sequence. Added hooks may run in any order
   * concurrently, but they are running before Akka internal shutdown
   * hooks, e.g. those shutting down Artery.
   */
  def addJvmShutdownHook[T](hook: => T): Unit = addCancellableJvmShutdownHook(hook)

  /**
   * Scala API: Add a JVM shutdown hook that will be run when the JVM process
   * begins its shutdown sequence. Added hooks may run in any order
   * concurrently, but they are running before Akka internal shutdown
   * hooks, e.g. those shutting down Artery.
   *
   * The returned ``Cancellable`` makes it possible to de-register the hook. For example
   * on actor system shutdown to avoid leaking references to the actor system in tests.
   *
   * For shutdown hooks that does not have any requirements on running before the Akka
   * shutdown hooks the standard library JVM shutdown hooks APIs are better suited.
   */
  @tailrec def addCancellableJvmShutdownHook[T](hook: => T): Cancellable = {
    if (runStarted.get == None) {
      val currentLatch = _jvmHooksLatch.get
      val newLatch = new CountDownLatch(currentLatch.getCount.toInt + 1)
      if (_jvmHooksLatch.compareAndSet(currentLatch, newLatch)) {
        val thread = new Thread {
          override def run(): Unit = {
            try hook
            finally _jvmHooksLatch.get.countDown()
          }
        }
        thread.setName(s"${system.name}-shutdown-hook-${newLatch.getCount}")
        try {
          Runtime.getRuntime.addShutdownHook(thread)
          new Cancellable {
            @volatile var cancelled = false
            def cancel(): Boolean = {
              try {
                if (Runtime.getRuntime.removeShutdownHook(thread)) {
                  cancelled = true
                  _jvmHooksLatch.get.countDown()
                  true
                } else {
                  false
                }
              } catch {
                case _: IllegalStateException =>
                  // shutdown already in progress
                  false
              }
            }
            def isCancelled: Boolean = cancelled
          }
        } catch {
          case e: IllegalStateException =>
            // Shutdown in progress, if CoordinatedShutdown is created via a JVM shutdown hook (Artery)
            log.warning("Could not addJvmShutdownHook, due to: {}", e.getMessage)
            _jvmHooksLatch.get.countDown()
            Cancellable.alreadyCancelled
        }
      } else
        addCancellableJvmShutdownHook(hook) // lost CAS, retry
    } else {
      Cancellable.alreadyCancelled
    }
  }

  /**
   * Java API: Add a JVM shutdown hook that will be run when the JVM process
   * begins its shutdown sequence. Added hooks may run in any order
   * concurrently, but they are running before Akka internal shutdown
   * hooks, e.g. those shutting down Artery.
   */
  def addJvmShutdownHook(hook: Runnable): Unit =
    addJvmShutdownHook(hook.run())

  /**
   * Java API: Add a JVM shutdown hook that will be run when the JVM process
   * begins its shutdown sequence. Added hooks may run in an order
   * concurrently, but they are running before Akka internal shutdown
   * hooks, e.g. those shutting down Artery.
   *
   * The returned ``Cancellable`` makes it possible to de-register the hook. For example
   * on actor system shutdown to avoid leaking references to the actor system in tests.
   *
   * For shutdown hooks that does not have any requirements on running before the Akka
   * shutdown hooks the standard library JVM shutdown hooks APIs are better suited.
   */
  def addCancellableJvmShutdownHook(hook: Runnable): Cancellable =
    addCancellableJvmShutdownHook(hook.run())

}
