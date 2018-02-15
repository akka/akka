/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.testkit.typed.internal.TestKitImpl
import akka.testkit.typed.scaladsl.{ TestKit ⇒ ScalaTestKit }
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration.Duration

object TestKit {

  def create(testClass: Class[_]): TestKit = new TestKit(new ScalaTestKit {
    override def name = testClass.getSimpleName
  })

  def create(testClass: Class[_], customConfig: Config) = new TestKit(new ScalaTestKit {
    override def name = testClass.getSimpleName
    override def config = customConfig
  })

  /**
   * Shutdown the given actor system and wait up to `duration` for shutdown to complete.
   * @param verifySystemShutdown Fail the test if the system fails to shut down, if false
   *                             an error is printed to stdout when the system did not shutdown but
   *                             no exception is thrown.
   */
  def shutdown(
    system:               ActorSystem[_],
    duration:             Duration,
    verifySystemShutdown: Boolean): Unit = {
    TestKitImpl.shutdown(system, duration, verifySystemShutdown)
  }

  /**
   * Shutdown the given actor system and wait up to `duration` for shutdown to complete.
   * If shutdown fails a warning is printed to stdout.
   */
  def shutdown(system: ActorSystem[_], duration: Duration): Unit = {
    shutdown(system, duration, false)
  }

  /**
   * Shutdown the given actor system and wait up to 5s for shutdown to complete.
   * If shutdown fails a warning is printed to stdout.
   */
  def shutdown(system: ActorSystem[_]): Unit = {
    shutdown(system, Duration.create(5, TimeUnit.SECONDS), false)
  }

}

/**
 * Java API: Test kit for typed actors, provides a typed actor system started on creation, used for all test cases
 * and shut down when `shutdown` is called.
 *
 * The actor system has a custom guardian that allows for spawning arbitrary actors using the `spawn` methods.
 *
 * Designed to work with any test framework, but framework glue code that calls shutdown after all tests has
 * run needs to be provided by the user.
 *
 * Use `TestKit.create` factories to construct manually or [[TestKitJunitResource]] to use together with JUnit tests
 */
final class TestKit protected (delegate: akka.testkit.typed.scaladsl.TestKit) {

  def timeout: Timeout = delegate.timeout
  def system: ActorSystem[Void] = delegate.system.asInstanceOf[ActorSystem[Void]]
  def scheduler: Scheduler = delegate.scheduler

  def spawn[T](behavior: Behavior[T]): ActorRef[T] = delegate.spawn(behavior)
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] = delegate.spawn(behavior, name)
  def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] = delegate.spawn(behavior, props)
  def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] = delegate.spawn(behavior, name, props)

  def createTestProbe[M](): TestProbe[M] = TestProbe.create(system)
  def createTestProbe[M](clazz: Class[M]): TestProbe[M] = TestProbe.create(system)

  def shutdownTestKit(): Unit = delegate.shutdownTestKit()

}
