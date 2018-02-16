package akka.testkit.typed.scaladsl

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.annotation.{ ApiMayChange, InternalApi }
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.internal.{ ActorTestKitGuardian, TestKitUtils }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Await
import scala.concurrent.duration._

object ActorTestKit {

  def shutdown(
    system:               ActorSystem[_],
    duration:             Duration,
    verifySystemShutdown: Boolean        = false): Unit =
    TestKitUtils.shutdown(system, duration, verifySystemShutdown)

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
 */
@ApiMayChange
trait ActorTestKit {
  /**
   * Actor system name based on the test it is mixed into, override to customize, or pass to constructor
   * if using [[ActorTestKit]] rather than [[ActorTestKit]]
   */
  protected def name: String = TestKitUtils.testNameFromCallStack(classOf[ActorTestKit])

  /**
   * Configuration the actor system is created with, override to customize, or pass to constructor
   * if using [[ActorTestKit]] rather than [[ActorTestKit]]
   */
  def config: Config = ConfigFactory.empty()

  /**
   * TestKit settings used in the tests, override or provide custom config to customize
   */
  protected implicit def testkitSettings = TestKitSettings(system)

  private val internalSystem: ActorSystem[ActorTestKitGuardian.TestKitCommand] =
    if (config == ConfigFactory.empty) ActorSystem(ActorTestKitGuardian.testKitGuardian, name)
    else ActorSystem(ActorTestKitGuardian.testKitGuardian, name, config)

  implicit final def system: ActorSystem[Nothing] = internalSystem

  implicit def scheduler = system.scheduler
  private val childName: Iterator[String] = Iterator.from(0).map(_.toString)

  implicit val timeout = testkitSettings.DefaultTimeout

  final def shutdownTestKit(): Unit = {
    // FIXME separate default timeout for shutdown?
    ActorTestKit.shutdown(system, timeout.duration)
  }

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  final def spawn[T](behavior: Behavior[T]): ActorRef[T] =
    spawn(behavior, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  final def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] =
    Await.result(internalSystem ? (ActorTestKitGuardian.SpawnActorAnonymous(behavior, _, props)), timeout.duration)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  final def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] =
    spawn(behavior, name, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  final def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
    Await.result(internalSystem ? (ActorTestKitGuardian.SpawnActor(name, behavior, _, props)), timeout.duration)

  // FIXME needed for Akka internal tests but, users shouldn't spawn system actors?
  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T], name: String): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, name), timeout.duration)

  @InternalApi
  private[akka] def systemActor[T](behavior: Behavior[T]): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, childName.next()), timeout.duration)
}
