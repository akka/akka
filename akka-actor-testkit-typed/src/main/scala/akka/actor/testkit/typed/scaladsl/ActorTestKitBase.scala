/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.Scheduler
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.internal.TestKitUtils
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object ActorTestKitBase {
  def testNameFromCallStack(): String = TestKitUtils.testNameFromCallStack(classOf[ActorTestKitBase])
}

/**
 * A base class for the [[ActorTestKit]], making it possible to have testing framework (e.g. ScalaTest)
 * manage the lifecycle of the testkit.
 *
 * An implementation for ScalaTest is [[ScalaTestWithActorTestKit]].
 *
 * Another abstract class that is testing framework specific should extend this class and
 * automatically shut down the `testKit` when the test completes or fails by implementing [[ActorTestKitBase#afterAll]].
 */
abstract class ActorTestKitBase(val testKit: ActorTestKit) {

  def this() = this(ActorTestKit(ActorTestKitBase.testNameFromCallStack()))

  /**
   * Use a custom config for the actor system.
   */
  def this(config: String) =
    this(ActorTestKit(ActorTestKitBase.testNameFromCallStack(), ConfigFactory.parseString(config)))

  /**
   * Use a custom config for the actor system.
   */
  def this(config: Config) = this(ActorTestKit(ActorTestKitBase.testNameFromCallStack(), config))

  /**
   * Use a custom config for the actor system, and a custom [[akka.actor.testkit.typed.TestKitSettings]].
   */
  def this(config: Config, settings: TestKitSettings) =
    this(ActorTestKit(ActorTestKitBase.testNameFromCallStack(), config, settings))

  // delegates of the TestKit api for minimum fuss
  /**
   * See corresponding method on [[ActorTestKit]]
   */
  implicit def system: ActorSystem[Nothing] = testKit.system

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  implicit def testKitSettings: TestKitSettings = testKit.testKitSettings

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  implicit def timeout: Timeout = testKit.timeout

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  implicit def scheduler: Scheduler = testKit.scheduler

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def spawn[T](behavior: Behavior[T]): ActorRef[T] = testKit.spawn(behavior)

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] = testKit.spawn(behavior, name)

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] = testKit.spawn(behavior, props)

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] = testKit.spawn(behavior, name, props)

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def createTestProbe[M](): TestProbe[M] = testKit.createTestProbe[M]()

  /**
   * See corresponding method on [[ActorTestKit]]
   */
  def createTestProbe[M](name: String): TestProbe[M] = testKit.createTestProbe(name)

  /**
   * To be implemented by "more" concrete class that can mixin `BeforeAndAfterAll` or similar,
   * for example `FlatSpecLike with BeforeAndAfterAll`. Implement by calling
   * `testKit.shutdownTestKit()`.
   */
  protected def afterAll(): Unit

}
