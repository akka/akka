package akka.remote.artery

import akka.actor.{ Actor, ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, Props, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory

import ArterySmokeTest._

object ArterySmokeTest {

  val commonConfig = """
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery {
         enabled = on
         #transport = tcp
         transport = aeron-udp
       }
     }
  """

}

class ArterySmokeTest extends AkkaSpec(commonConfig) with ImplicitSender {

  val configB = ConfigFactory.parseString("akka.remote.artery.port = 20201")
  val systemB = ActorSystem("systemB", configB.withFallback(system.settings.config))
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  println(addressB)
  val rootB = RootActorPath(addressB)

  "Artery" must {

    "be able to identify a remote actor and ping it" in {
      val actorOnSystemB = systemB.actorOf(Props(new Actor {
        def receive = {
          case "ping" ⇒ sender() ! "pong"
        }
      }), "echo")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      remoteRef ! "ping"
      expectMsg("pong")

      remoteRef ! "ping"
      expectMsg("pong")

      remoteRef ! "ping"
      expectMsg("pong")

    }

  }

  override def afterTermination(): Unit = shutdown(systemB)

}
