/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.test

import org.junit.runner.RunWith
import org.junit._
import org.junit.Assert._
import org.scalatest.{ BeforeAndAfterAll, Suite }
import org.scalatest.matchers.MustMatchers
import org.scalatest.junit.{ AssertionsForJUnit, JUnitSuite }
import org.ops4j.pax.exam.junit.{ JUnit4TestRunner, Configuration, ExamReactorStrategy }
import org.ops4j.pax.exam.Option
import org.ops4j.pax.exam.CoreOptions._
import org.ops4j.pax.exam.spi.reactors.AllConfinedStagedReactorFactory;

import org.osgi.framework.{ BundleContext, BundleActivator }
import javax.inject.Inject
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.util.duration._
import PingPong._

/**
 * Testing akka-actor and akka-osgi bundles. Checking activator
 * and all classes can be executed properly.
 *
 * @author Nepomuk Seiler
 */
@RunWith(classOf[JUnit4TestRunner])
@ExamReactorStrategy(Array(classOf[AllConfinedStagedReactorFactory]))
class LocalActorTest extends JUnitSuite /* with Suite with MustMatchers */ {

  @Inject
  var ctx: BundleContext = _

  var activator: BundleActivator = _

  @Configuration
  def configure: Array[Option] = {
    options(
      junitBundles(),
      bundle("http://download.scala-ide.org/sdk/e37/scala210/dev/site/plugins/org.scala-ide.scala.library_2.10.0.v20121011-125927-RC1-25ad7876a9.jar"),
      mavenBundle("org.scalatest", "scalatest_2.9.0", "1.8"))
  }

  /**
   * Imitating a bundle startup
   */
  @Before
  def setUp {
    activator = new TestActivator
    activator.start(ctx)
  }

  /**
   * Imitating a bundle shutdown
   */
  @After
  def tearDown {
    activator.stop(ctx)
  }

  @Test
  def testBundleContextAvailable {
    assertNotNull(ctx)
  }

  @Test
  def testActorSystem() {
    val sysRef = ctx.getServiceReference(classOf[ActorSystem].getName)
    assertNotNull(sysRef)

    val system = ctx.getService(sysRef).asInstanceOf[ActorSystem]
    assertNotNull(system)

    val pong = system.actorOf(Props[PongActor], name = "pong")
    implicit val timeout = Timeout(5 seconds)
    val result = Await.result(pong ? Ping, timeout.duration) //must be(Pong)
    assertEquals("Pong", result.toString)
  }

}
