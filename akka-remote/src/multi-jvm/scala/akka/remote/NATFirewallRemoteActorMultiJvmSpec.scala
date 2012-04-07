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

  def NrOfNodes = 5

  class SomeActor extends Actor with Serializable {
    def receive = {
      case "hi" ⇒ sender ! "hello"
    }
  }

  def setup(firewall: String, addresses: String, host: String, port: Int): ActorSystem = {
    val config = ConfigFactory.parseString("""
    akka{
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
      }
      remote{
         transport = "akka.remote.netty.NettyRemoteTransport"
         nat-firewall = %s
         nat-firewall-addresses = [%s]
         netty {
          hostname = "%s"
          port = %d
        }
      }
    }
  """.format(firewall, addresses, host, port))
    val system = ActorSystem("nat", config)
    system.actorOf(Props[SomeActor], "service-hello")
    system

  }


}

//whitelist with empty addresses
class NATFirewallRemoteActorMultiJvmNode1 extends AkkaSpec(setup("whitelist", "", "0.0.0.0", 2552)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

//whitelist with addresses specified
class NATFirewallRemoteActorMultiJvmNode2 extends AkkaSpec(setup("whitelist", """"127.0.0.1:3663"""", "0.0.0.0", 3663)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}
//blacklist with empty addresses
class NATFirewallRemoteActorMultiJvmNode3 extends AkkaSpec(setup("blacklist", "", "0.0.0.0", 4774)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}
//blacklist with addresses specified
class NATFirewallRemoteActorMultiJvmNode4 extends AkkaSpec(setup("blacklist", """"127.0.0.1:5885"""", "0.0.0.0", 5885)) with MultiJvmSync {

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")

      barrier("done")
    }
  }
}

class NATFirewallRemoteActorMultiJvmNode5 extends AkkaSpec(setup("whitelist", "", "127.0.0.1", 6996)) with MultiJvmSync with DefaultTimeout {


  val nodes = NrOfNodes

  "NAT Firewall" must {
    "allow or dissalow messages properly in" in {
      barrier("start")
      val actor1 = system.actorFor("akka://nat@127.0.0.1:2552/user/service-hello")
      val actor2 = system.actorFor("akka://nat@127.0.0.1:3663/user/service-hello")
      val actor3 = system.actorFor("akka://nat@127.0.0.1:4774/user/service-hello")
      val actor4 = system.actorFor("akka://nat@127.0.0.1:5885/user/service-hello")

      evaluating {
        Await.result(actor1 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      Await.result(actor2 ? "hi", 250 millis).asInstanceOf[String] must be("hello")

      Await.result(actor3 ? "hi", 250 millis).asInstanceOf[String] must be("hello")

      evaluating {
        Await.result(actor4 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      val actor5 = system.actorFor("akka://notnat@127.0.0.1:2552/user/service-hello")
      val actor6 = system.actorFor("akka://notnat@127.0.0.1:3663/user/service-hello")
      val actor7 = system.actorFor("akka://notnat@127.0.0.1:4774/user/service-hello")
      val actor8 = system.actorFor("akka://notnat@127.0.0.1:5885/user/service-hello")

      evaluating {
        Await.result(actor5 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      evaluating {
        Await.result(actor6 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      evaluating {
        Await.result(actor7 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

      evaluating {
        Await.result(actor8 ? "hi", 250 millis).asInstanceOf[String]
      } must produce[TimeoutException]

 // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(system.actorFor("service-hello"))
      barrier("done")
    }
  }
}

