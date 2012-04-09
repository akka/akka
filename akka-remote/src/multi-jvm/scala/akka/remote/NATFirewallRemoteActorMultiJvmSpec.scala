package akka.remote

import akka.testkit._
import akka.dispatch.Await

import NATFirewallRemoteActorMultiJvmSpec._
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorSystem, Actor, Props}
import akka.pattern.ask
import akka.util.duration._
import java.util.concurrent.TimeoutException


object NATFirewallRemoteActorMultiJvmSpec {

  def NrOfNodes = 3

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hi" ⇒ sender ! "hello"
    }
  }

  def setup(addresses: String, host: String, port: Int): ActorSystem = {
    val config = ConfigFactory.parseString("""
    akka{
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
      }
      remote{
         transport = "akka.remote.netty.NettyRemoteTransport"
         public-addresses = [%s]
         netty {
          hostname = "%s"
          port = %d
        }
      }
    }
  """.format(addresses, host, port))
    val system = ActorSystem("nat", config)
    system.actorOf(Props[SomeActor], "service-hello")
    system

  }


}

//empty public-addresses
class NATFirewallRemoteActorMultiJvmNode1 extends AkkaSpec(setup("", "0.0.0.0", 2552)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

//public-addresses specified
class NATFirewallRemoteActorMultiJvmNode2 extends AkkaSpec(setup(""""127.0.0.1:3663"""", "0.0.0.0", 3663)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

class NATFirewallRemoteActorMultiJvmNode3 extends AkkaSpec(setup("", "127.0.0.1", 6996)) with MultiJvmSync with DefaultTimeout {


  val nodes = NrOfNodes

  "NAT Firewall" must {
    "allow or dissalow messages properly in" in {
      barrier("start")
      val actor1 = system.actorFor("akka://nat@127.0.0.1:2552/user/service-hello")
      val actor2 = system.actorFor("akka://nat@127.0.0.1:3663/user/service-hello")

      evaluating {
        Await.result(actor1 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      Await.result(actor2 ? "hi", 250 millis).asInstanceOf[String] must be("hello")


      val actor5 = system.actorFor("akka://notnat@127.0.0.1:2552/user/service-hello")
      val actor6 = system.actorFor("akka://notnat@127.0.0.1:3663/user/service-hello")


      evaluating {
        Await.result(actor5 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      evaluating {
        Await.result(actor6 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      barrier("done")
    }
  }
}

