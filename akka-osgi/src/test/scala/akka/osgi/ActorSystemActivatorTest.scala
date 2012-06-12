package akka.osgi

import org.scalatest.FlatSpec
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout
import de.kalpatec.pojosr.framework.launch.BundleDescriptor
import test.TestActorSystemActivator
import test.PingPong._
import PojoSRTestSupport.bundle

/**
 * Test cases for {@link ActorSystemActivator}
 */
class ActorSystemActivatorTest extends FlatSpec with PojoSRTestSupport {

  val TEST_BUNDLE_NAME = "akka.osgi.test.activator"

  val testBundles: Seq[BundleDescriptor] = Seq(
    bundle(TEST_BUNDLE_NAME).withActivator(classOf[TestActorSystemActivator]))

  "ActorSystemActivator" should "start and register the ActorSystem when bundle starts" in {
    val system = serviceForType[ActorSystem]
    val actor = system.actorFor("/user/pong")

    implicit val timeout = Timeout(5 seconds)
    val future = actor ? Ping
    val result = Await.result(future, timeout.duration)
    assert(result != null)
  }

  it should "stop the ActorSystem when bundle stops" in {
    val system = serviceForType[ActorSystem]
    assert(!system.isTerminated)

    bundleForName(TEST_BUNDLE_NAME).stop()

    system.awaitTermination()
    assert(system.isTerminated)
  }

}
