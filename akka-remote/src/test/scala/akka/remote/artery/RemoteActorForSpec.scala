/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorRef, ActorRefScope, PoisonPill, Props }
import akka.pattern.ask
import akka.remote.RemoteActorRef
import akka.remote.RemotingSpec.ActorForReq
import akka.testkit.{ EventFilter, _ }
import akka.util.Timeout

import scala.concurrent.duration._

object RemoteActorForSpec {
  final case class ActorForReq(s: String) extends JavaSerializable
}

class RemoteActorForSpec extends ArteryMultiNodeSpec("akka.loglevel=INFO") with ImplicitSender with DefaultTimeout {

  val remoteSystem = newRemoteSystem()
  val remotePort = port(remoteSystem)

  "Remote lookups" should {

    "support remote look-ups" in {
      remoteSystem.actorOf(TestActors.echoActorProps, "remote-look-ups")
      val remoteRef = localSystem.actorFor(s"akka://${remoteSystem.name}@localhost:$remotePort/user/remote-look-ups")
      remoteRef ! "ping"
      expectMsg("ping")
    }

    // FIXME does not log anything currently
    "send warning message for wrong address" ignore {
      filterEvents(EventFilter.warning(pattern = "Address is now gated for ", occurrences = 1)) {
        localSystem.actorFor("akka://nonexistingsystem@localhost:12346/user/echo") ! "ping"
      }
    }

    "support ask" in {
      remoteSystem.actorOf(TestActors.echoActorProps, "support-ask")
      val remoteRef = localSystem.actorFor(s"akka://${remoteSystem.name}@localhost:$remotePort/user/support-ask")

      implicit val timeout: Timeout = 10.seconds
      (remoteRef ? "ping").futureValue should ===("ping")
    }

    "send dead letters on remote if actor does not exist" in {
      EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
        localSystem.actorFor(s"akka://${remoteSystem.name}@localhost:$remotePort/dead-letters-on-remote") ! "buh"
      }(remoteSystem)
    }

    // FIXME needs remote deployment section
    "look-up actors across node boundaries" ignore {
      val l = localSystem.actorOf(Props(new Actor {
        def receive = {
          case (p: Props, n: String) ⇒ sender() ! context.actorOf(p, n)
          case ActorForReq(s)        ⇒ sender() ! context.actorFor(s)
        }
      }), "looker1")
      // child is configured to be deployed on remote-sys (remoteSystem)
      l ! ((TestActors.echoActorProps, "child"))
      val child = expectMsgType[ActorRef]
      // grandchild is configured to be deployed on RemotingSpec (system)
      child ! ((TestActors.echoActorProps, "grandchild"))
      val grandchild = expectMsgType[ActorRef]
      grandchild.asInstanceOf[ActorRefScope].isLocal should ===(true)
      grandchild ! 43
      expectMsg(43)
      val myref = localSystem.actorFor(system / "looker1" / "child" / "grandchild")
      myref.isInstanceOf[RemoteActorRef] should ===(true)
      myref ! 44
      expectMsg(44)
      lastSender should ===(grandchild)
      lastSender should be theSameInstanceAs grandchild
      child.asInstanceOf[RemoteActorRef].getParent should ===(l)
      localSystem.actorFor("/user/looker1/child") should be theSameInstanceAs child
      (l ? ActorForReq("child/..")).mapTo[AnyRef].futureValue should be theSameInstanceAs l
      (localSystem.actorFor(system / "looker1" / "child") ? ActorForReq("..")).mapTo[AnyRef].futureValue should be theSameInstanceAs l

      watch(child)
      child ! PoisonPill
      expectMsg("postStop")
      expectTerminated(child)
      l ! ((TestActors.echoActorProps, "child"))
      val child2 = expectMsgType[ActorRef]
      child2 ! 45
      expectMsg(45)
      // msg to old ActorRef (different uid) should not get through
      child2.path.uid should not be (child.path.uid)
      child ! 46
      expectNoMsg(1.second)
      system.actorFor(system / "looker1" / "child") ! 47
      expectMsg(47)
    }

  }

}
