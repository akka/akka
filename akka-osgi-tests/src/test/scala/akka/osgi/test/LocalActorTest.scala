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
import org.ops4j.pax.exam.junit.{ JUnit4TestRunner, Configuration }
import org.ops4j.pax.exam.Option
import org.ops4j.pax.exam.CoreOptions._
import org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.karafDistributionConfiguration

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
class LocalActorTest extends JUnitSuite /* with Suite with MustMatchers */ {

  @Inject
  var ctx: BundleContext = _

  var activator: BundleActivator = _

  @Configuration
  def configure: Array[Option] = {
    options(
      junitBundles(),
      bundle("http://download.scala-ide.org/releases-210/milestone/site/plugins/org.scala-ide.scala.library_2.10.0.v20120710-144914-M5-026a70d555.jar"),
      // mavenBundle("org.scala-lang", "scala-library", Versions.SCALA), //Doesn't work as scala-library is not a bundle
      mavenBundle("com.typesafe.akka", "akka-actor", Versions.AKKA),
      mavenBundle("com.typesafe.akka", "akka-osgi", Versions.AKKA),
      mavenBundle("com.typesafe", "config", "0.4.1"),
      //mavenBundle("org.scalatest", "scalatest_2.10.0-M5", "1.9-2.10.0-M5-B2"),
      bundle("http://repository.mukis.de/scalatest_2.10.0_1.9.2_M6.jar"),
      karafDistributionConfiguration().frameworkUrl(
        maven().groupId("org.apache.karaf").artifactId("apache-karaf").`type`("zip"))
        .karafVersion("2.2.8").name("Apache Karaf"))
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
