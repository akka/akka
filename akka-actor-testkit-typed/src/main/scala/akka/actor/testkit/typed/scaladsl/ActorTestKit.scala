/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.internal.ActorTestKitGuardian
import akka.actor.testkit.typed.internal.TestKitUtils
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.annotation.InternalApi
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object ActorTestKit {

  /**
   * Create a testkit named from the class that is calling this method.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   *
   * Config loaded from `application-test.conf` if that exists, otherwise
   * using default configuration from the reference.conf resources that ship with the Akka libraries.
   * The application.conf of your project is not used in this case.
   */
  def apply(): ActorTestKit =
    new ActorTestKit(
      name = TestKitUtils.testNameFromCallStack(classOf[ActorTestKit]),
      config = ApplicationTestConfig,
      settings = None)

  /**
   * Create a named testkit.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   *
   * Config loaded from `application-test.conf` if that exists, otherwise
   * using default configuration from the reference.conf resources that ship with the Akka libraries.
   * The application.conf of your project is not used in this case.
   */
  def apply(name: String): ActorTestKit =
    new ActorTestKit(name = TestKitUtils.scrubActorSystemName(name), config = ApplicationTestConfig, settings = None)

  /**
   * Create a testkit named from the class that is calling this method,
   * and use a custom config for the actor system.
   *
   * It will create an [[akka.actor.typed.ActorSystem]] with this name,
   * e.g. threads will include the name.
   * When the test has completed you should terminate the `ActorSystem` and
   * the testkit with [[ActorTestKit#shutdownTestKit]].
   */
  def apply(customConfig: Config): ActorTestKit =
    new ActorTestKit(
      name = TestKitUtils.testNameFromCallStack(classOf[ActorTestKit]),
      config = customConfig,
      settings = None)

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

  /**
   * Config loaded from `application-test.conf`, which is used if no specific config is given.
   */
  val ApplicationTestConfig: Config = ConfigFactory.load("application-test")

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
final class ActorTestKit private[akka] (val name: String, val config: Config, settings: Option[TestKitSettings]) {

  implicit def testKitSettings: TestKitSettings =
    settings.getOrElse(TestKitSettings(system))

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] val internalSystem: ActorSystem[ActorTestKitGuardian.TestKitCommand] =
    ActorSystem(ActorTestKitGuardian.testKitGuardian, name, config)

  implicit def system: ActorSystem[Nothing] = internalSystem

  private val childName: Iterator[String] = Iterator.from(0).map(_.toString)

  implicit val timeout: Timeout = testKitSettings.DefaultTimeout

  def scheduler: Scheduler = system.scheduler

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

  /**
   * Additional testing utilities for serialization.
   */
  val serializationTestKit: SerializationTestKit = new SerializationTestKit(internalSystem)

  // FIXME needed for Akka internal tests but, users shouldn't spawn system actors?
  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T], name: String): ActorRef[T] =
    system.systemActorOf(behavior, name)

  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T]): ActorRef[T] =
    system.systemActorOf(behavior, childName.next())
}
