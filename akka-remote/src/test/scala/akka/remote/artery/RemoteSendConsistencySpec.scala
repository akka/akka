package akka.remote.artery

import akka.actor.{ Actor, ActorIdentity, ActorSystem, Deploy, ExtendedActorSystem, Identify, Props, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.ConfigFactory
import RemoteSendConsistencySpec._
import akka.actor.Actor.Receive

object RemoteSendConsistencySpec {

  val commonConfig = """
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
     }
  """

}

class RemoteSendConsistencySpec extends AkkaSpec(commonConfig) with ImplicitSender {

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

    "be able to send messages concurrently preserving order" in {
      val actorOnSystemB = systemB.actorOf(Props(new Actor {
        def receive = {
          case i: Int ⇒ sender() ! i
        }
      }), "echo2")

      val remoteRef = {
        system.actorSelection(rootB / "user" / "echo2") ! Identify(None)
        expectMsgType[ActorIdentity].ref.get
      }

      val senderProps = Props(new Actor {
        var counter = 1000
        remoteRef ! 1000

        override def receive: Receive = {
          case i: Int ⇒
            if (i != counter) testActor ! s"Failed, expected $counter got $i"
            else if (counter == 0) {
              testActor ! "success"
              context.stop(self)
            } else {
              counter -= 1
              remoteRef ! counter
            }
        }
      }).withDeploy(Deploy.local)

      system.actorOf(senderProps)
      system.actorOf(senderProps)
      system.actorOf(senderProps)
      system.actorOf(senderProps)

      expectMsg("success")
      expectMsg("success")
      expectMsg("success")
      expectMsg("success")
    }

  }

  override def afterTermination(): Unit = shutdown(systemB)

}
