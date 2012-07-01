package akka.osgi.aries.blueprint

import org.scalatest.WordSpec
import akka.actor.ActorSystem
import de.kalpatec.pojosr.framework.launch.BundleDescriptor
import akka.osgi.PojoSRTestSupport
import akka.osgi.PojoSRTestSupport.bundle
import org.scalatest.matchers.MustMatchers

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

class SimpleNamespaceHandlerTest extends WordSpec with MustMatchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles: Seq[BundleDescriptor] = buildTestBundles(Seq(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("simple.xml"))))

  "simple.xml" must {
    "set up ActorSystem when bundle starts" in {
      val system = serviceForType[ActorSystem]
      assert(system != null)
    }

    "stop the ActorSystem when bundle stops" in {
      val system = serviceForType[ActorSystem]
      assert(!system.isTerminated)

      bundleForName(TEST_BUNDLE_NAME).stop()

      system.awaitTermination()
      assert(system.isTerminated)
    }
  }

}

class ConfigNamespaceHandlerTest extends WordSpec with MustMatchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles: Seq[BundleDescriptor] = buildTestBundles(Seq(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("config.xml"))))

  "config.xml" must {
    "set up ActorSystem when bundle starts" in {
      val system = serviceForType[ActorSystem]
      assert(system != null)

      assert(system.settings.config.getString("some.config.key") == "value")
    }

    "stop the ActorSystem when bundle stops" in {
      val system = serviceForType[ActorSystem]
      assert(!system.isTerminated)

      bundleForName(TEST_BUNDLE_NAME).stop()

      system.awaitTermination()
      assert(system.isTerminated)
    }
  }

}

class DependencyInjectionNamespaceHandlerTest extends WordSpec with MustMatchers with PojoSRTestSupport {

  import NamespaceHandlerTest._

  val testBundles: Seq[BundleDescriptor] = buildTestBundles(Seq(
    AKKA_OSGI_BLUEPRINT,
    bundle(TEST_BUNDLE_NAME).withBlueprintFile(getClass.getResource("injection.xml"))))

  "injection.xml" must {

    "set up bean containing ActorSystem" in {
      val bean = serviceForType[ActorSystemAwareBean]
      assert(bean != null)
      assert(bean.system != null)
    }
  }

}
