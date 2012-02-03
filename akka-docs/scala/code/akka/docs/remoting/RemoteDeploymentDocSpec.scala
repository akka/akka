/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.remoting

import akka.actor.{ ExtendedActorSystem, ActorSystem, Actor, ActorRef }
import akka.testkit.{ AkkaSpec, ImplicitSender }
//#import
import akka.actor.{ Props, Deploy, Address, AddressExtractor }
import akka.remote.RemoteScope
//#import

object RemoteDeploymentDocSpec {

  class Echo extends Actor {
    def receive = {
      case x ⇒ sender ! self
    }
  }

}

class RemoteDeploymentDocSpec extends AkkaSpec("""
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.remote.netty.port = 0
""") with ImplicitSender {

  import RemoteDeploymentDocSpec._

  val other = ActorSystem("remote", system.settings.config)
  val address = other.asInstanceOf[ExtendedActorSystem].provider.getExternalAddressFor(Address("akka", "s", Some("host"), Some(1))).get

  override def atTermination() { other.shutdown() }

  "demonstrate programmatic deployment" in {
    //#deploy
    val ref = system.actorOf(Props[Echo].withDeploy(Deploy(scope = RemoteScope(address))))
    //#deploy
    ref.path.address must be(address)
    ref ! "test"
    expectMsgType[ActorRef].path.address must be(address)
  }

  "demonstrate address extractor" in {
    //#make-address
    val one = AddressExtractor("akka://sys@host:1234")
    val two = Address("akka", "sys", "host", 1234) // this gives the same
    //#make-address
    one must be === two
  }

}