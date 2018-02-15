/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import akka.actor.Scheduler
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.util.Timeout
import com.typesafe.config.Config
import org.junit.{ ClassRule, Rule }
import org.junit.rules.ExternalResource

/**
 * A Junit external resource for the testkit, making it possible to have Junit manage the lifecycle of the testkit.
 * The testkit will be automatically shut down when the test completes fails.
 *
 *
 * Example:
 * {{{
 * public class MyActorTest {
 *   @ClassRule
 *   public static final TestKitResource testKit = new TestKitResource(MyActorTest.class);
 *
 *   @Test
 *   public void testBlah() throws Exception {
 * 	   // spawn actors etc using the testKit
 *   }
 * }
 * }}}
 */
class TestKitJunitResource(_kit: TestKit) extends ExternalResource {

  def this(testClass: Class[_]) = this(TestKit.create(testClass))
  def this(testClass: Class[_], customConfig: Config) = this(TestKit.create(testClass, customConfig))

  @Rule
  final val testKit = _kit

  // delegates of the TestKit api for minimum fuss
  def system: ActorSystem[Void] = testKit.system
  def timeout: Timeout = testKit.timeout
  def scheduler: Scheduler = testKit.scheduler

  def spawn[T](behavior: Behavior[T]): ActorRef[T] = testKit.spawn(behavior)
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] = testKit.spawn(behavior, name)
  def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] = testKit.spawn(behavior, props)
  def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] = testKit.spawn(behavior, name, props)

  def createTestProbe[M](): TestProbe[M] = testKit.createTestProbe[M]()
  def createTestProbe[M](clazz: Class[M]): TestProbe[M] = testKit.createTestProbe(clazz)

  override def after(): Unit = {
    testKit.shutdownTestKit()
  }

}
