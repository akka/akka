package akka.osgi

import java.util.{ ServiceLoader, HashMap }
import de.kalpatec.pojosr.framework.launch.{ ClasspathScanner, PojoServiceRegistryFactory }
import org.scalatest.FlatSpec
import org.osgi.framework.BundleContext
import akka.actor.{ Actor, Props, ActorSystem }
import akka.pattern.ask
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout

/**
 * Test cases for {@link ActorSystemActivator}
 */
class ActorSystemActivatorTest extends FlatSpec {

  abstract class TestMessage

  case object Ping extends TestMessage
  case object Pong extends TestMessage

  class PongActor extends Actor {
    def receive = {
      case Ping ⇒
        sender ! Pong
    }
  }

  lazy val context: BundleContext = {
    val config = new HashMap[String, AnyRef]();
    val loader = ServiceLoader.load(classOf[PojoServiceRegistryFactory]);
    val registry = loader.iterator().next().newPojoServiceRegistry(config);
    registry.getBundleContext
  }

  val activator = new ActorSystemActivator {
    def configure(system: ActorSystem) {
      system.actorOf(Props(new PongActor), name = "pong")
    }
  }

  "ActorSystemActivator" should "start and register the ActorSystem on start" in {

    activator.start(context)

    val reference = context.getServiceReference(classOf[ActorSystem].getName)
    assert(reference != null)

    val system = context.getService(reference).asInstanceOf[ActorSystem]
    val actor = system.actorFor("/user/pong")

    implicit val timeout = Timeout(5 seconds)
    val future = actor ? Ping
    val result = Await.result(future, timeout.duration)
    assert(result != null)
  }

  it should "stop the ActorSystem on bundle stop" in {
    val reference = context.getServiceReference(classOf[ActorSystem].getName)
    assert(reference != null)

    val system = context.getService(reference).asInstanceOf[ActorSystem]
    assert(!system.isTerminated)

    activator.stop(context)

    system.awaitTermination()
    assert(system.isTerminated)
  }

}
