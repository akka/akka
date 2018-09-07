/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.actor.testkit.typed.TestKitSettings
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span

/**
 * A ScalaTest base class for the [[ActorTestKit]], making it possible to have ScalaTest manage the lifecycle of the testkit.
 * The testkit will be automatically shut down when the test completes or fails.
 *
 * Note that ScalaTest is not provided as a transitive dependency of the testkit module but must be added explicitly
 * to your project to use this.
 *
 * This is a `WordSpec`, other ScalaTest styles can be implemented in a similar abstract class like this one.
 * For example `with FlatSpecLike with BeforeAndAfterAll` and implementing the `afterAll` method by calling
 * `testKit.shutdownTestKit()`.
 */
abstract class ActorTestKitWordSpec(testKit: ActorTestKit) extends ActorTestKitBase(testKit)
  with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with Eventually {

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

  /**
   * `PatienceConfig` from [[akka.actor.testkit.typed.TestKitSettings#DefaultTimeout]]
   */
  implicit val patience: PatienceConfig =
    PatienceConfig(testKit.testKitSettings.DefaultTimeout.duration, Span(100, org.scalatest.time.Millis))
}

