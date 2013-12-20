/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.aries.blueprint

import org.scalatest.WordSpec
import akka.actor.ActorSystem
import de.kalpatec.pojosr.framework.launch.BundleDescriptor
import akka.osgi.PojoSRTestSupport
import akka.osgi.PojoSRTestSupport.bundle
import org.scalatest.Matchers

/**
 * Test cases for {@link ActorSystemActivator}
 */
object NamespaceHandlerTest {

  /*
   * Bundle-SymbolicName to easily find our test bundle
   */
  val TEST_BUNDLE_NAME = "akka.osgi.test.aries.namespace"

  /*
   * Bundle descriptor representing the akka-osgi bundle itself
   */
  val AKKA_OSGI_BLUEPRINT =
    bundle("akka-osgi").withBlueprintFile(getClass.getResource("/OSGI-INF/blueprint/akka-namespacehandler.xml"))

}

class SimpleNamespaceHandlerTest extends WordSpec with Matchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles = buildTestBundles(List(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("simple.xml"))))

  "simple.xml" must {
    "set up ActorSystem when bundle starts" in {
      filterErrors() {
        serviceForType[ActorSystem] should not be (null)
      }
    }

    "stop the ActorSystem when bundle stops" in {
      filterErrors() {
        val system = serviceForType[ActorSystem]
        system.isTerminated should be(false)

        bundleForName(TEST_BUNDLE_NAME).stop()

        system.awaitTermination()
        system.isTerminated should be(true)
      }
    }
  }

}

class ConfigNamespaceHandlerTest extends WordSpec with Matchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles = buildTestBundles(List(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("config.xml"))))

  "config.xml" must {
    "set up ActorSystem when bundle starts" in {
      filterErrors() {
        val system = serviceForType[ActorSystem]
        system should not be (null)
        system.settings.config.getString("some.config.key") should be("value")
      }
    }

    "stop the ActorSystem when bundle stops" in {
      filterErrors() {
        val system = serviceForType[ActorSystem]
        system.isTerminated should be(false)

        bundleForName(TEST_BUNDLE_NAME).stop()

        system.awaitTermination()
        system.isTerminated should be(true)
      }
    }
  }

}

class DependencyInjectionNamespaceHandlerTest extends WordSpec with Matchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles = buildTestBundles(List(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("injection.xml"))))

  "injection.xml" must {

    "set up bean containing ActorSystem" in {
      filterErrors() {
        val bean = serviceForType[ActorSystemAwareBean]
        bean should not be (null)
        bean.system should not be (null)
      }
    }
  }

}
