/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import java.util.concurrent.TimeoutException

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.Scheduler
import akka.annotation.ApiMayChange
import akka.annotation.InternalApi
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.internal.ActorTestKitGuardian
import akka.actor.testkit.typed.internal.TestKitUtils
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.util.Timeout

object ActorTestKit {

  /**
   * Create a testkit named from the class that is calling this method.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   */
  def apply(): ActorTestKit =
    new ActorTestKit(
      name = TestKitUtils.testNameFromCallStack(classOf[ActorTestKit]),
      config = noConfigSet,
      settings = None)

  /**
   * Create a named testkit.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   */
  def apply(name: String): ActorTestKit =
    new ActorTestKit(name = TestKitUtils.scrubActorSystemName(name), config = noConfigSet, settings = None)

  /**
   * Create a named testkit, and use a custom config for the actor system.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   */
  def apply(name: String, customConfig: Config): ActorTestKit =
    new ActorTestKit(name = TestKitUtils.scrubActorSystemName(name), config = customConfig, settings = None)

  /**
   * Create a named testkit, and use a custom config for the actor system,
   * and a custom [[akka.actor.testkit.typed.TestKitSettings]]
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   */
  def apply(name: String, customConfig: Config, settings: TestKitSettings): ActorTestKit =
    new ActorTestKit(name = TestKitUtils.scrubActorSystemName(name), config = customConfig, settings = Some(settings))

  /**
   * Shutdown the given [[akka.actor.typed.ActorSystem]] and block until it shuts down,
   * if more time than `TestKitSettings.DefaultActorSystemShutdownTimeout` passes an exception is thrown
   */
  def shutdown(system: ActorSystem[_]): Unit = {
    val settings = TestKitSettings(system)
    TestKitUtils.shutdown(system, settings.DefaultActorSystemShutdownTimeout, settings.ThrowOnShutdownTimeout)
  }

  /**
   * Shutdown the given [[akka.actor.typed.ActorSystem]] and block until it shuts down
   * or the `duration` hits. If the timeout hits `verifySystemShutdown` decides
   */
  def shutdown(system: ActorSystem[_], timeout: Duration, throwIfShutdownFails: Boolean = false): Unit =
    TestKitUtils.shutdown(system, timeout, throwIfShutdownFails)

  // place holder for no custom config specified to avoid the boilerplate
  // of an option for config in the trait
  private val noConfigSet = ConfigFactory.parseString("")

}

/**
 * Testkit for asynchronous testing of typed actors, meant for mixing into the test class.
 *
 * Provides a typed actor system started on creation, used for all test cases and shut down when `shutdown` is called.
 *
 * The actor system has a custom guardian that allows for spawning arbitrary actors using the `spawn` methods.
 *
 * Designed to work with any test framework, but framework glue code that calls shutdown after all tests has
 * run needs to be provided by the user.
 *
 * For synchronous testing of a `Behavior` see [[BehaviorTestKit]]
 */
@ApiMayChange
final class ActorTestKit private[akka] (val name: String, val config: Config, settings: Option[TestKitSettings]) {

  implicit def testKitSettings: TestKitSettings =
    settings.getOrElse(TestKitSettings(system))

  private val internalSystem: ActorSystem[ActorTestKitGuardian.TestKitCommand] =
    if (config eq ActorTestKit.noConfigSet) ActorSystem(ActorTestKitGuardian.testKitGuardian, name)
    else ActorSystem(ActorTestKitGuardian.testKitGuardian, name, config)

  implicit def system: ActorSystem[Nothing] = internalSystem

  implicit def scheduler: Scheduler = system.scheduler
  private val childName: Iterator[String] = Iterator.from(0).map(_.toString)

  implicit val timeout: Timeout = testKitSettings.DefaultTimeout

  def shutdownTestKit(): Unit = {
    ActorTestKit.shutdown(
      system,
      testKitSettings.DefaultActorSystemShutdownTimeout,
      testKitSettings.ThrowOnShutdownTimeout)
  }

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T]): ActorRef[T] =
    spawn(behavior, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] =
    Await.result(internalSystem.ask(ActorTestKitGuardian.SpawnActorAnonymous(behavior, _, props)), timeout.duration)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] =
    spawn(behavior, name, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
    Await.result(internalSystem.ask(ActorTestKitGuardian.SpawnActor(name, behavior, _, props)), timeout.duration)

  /**
   * Stop the actor under test and wait until it terminates.
   * It can only be used for actors that were spawned by this `ActorTestKit`.
   * Other actors will not be stopped by this method.
   */
  def stop[T](ref: ActorRef[T], max: FiniteDuration = timeout.duration): Unit =
    try {
      Await.result(internalSystem.ask { x: ActorRef[ActorTestKitGuardian.Ack.type] =>
        ActorTestKitGuardian.StopActor(ref, x)
      }, max)
    } catch {
      case _: TimeoutException =>
        assert(false, s"timeout ($max) during stop() waiting for actor [${ref.path}] to stop")
    }

  /**
   * Shortcut for creating a new test probe for the testkit actor system
   * @tparam M the type of messages the probe should accept
   */
  def createTestProbe[M](): TestProbe[M] = TestProbe()(system)

  /**
   * Shortcut for creating a new named test probe for the testkit actor system
   * @tparam M the type of messages the probe should accept
   */
  def createTestProbe[M](name: String): TestProbe[M] = TestProbe(name)(system)

  // FIXME needed for Akka internal tests but, users shouldn't spawn system actors?
  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T], name: String): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, name), timeout.duration)

  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T]): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, childName.next()), timeout.duration)
}
