/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ Actor, ActorIdentity, ActorRef, ActorRefScope, ActorSelection, ActorSystem, ExtendedActorSystem, Identify, PoisonPill, Props, Terminated }
import akka.remote.RARP
import akka.testkit.{ AkkaSpec, ImplicitSender, SocketUtil, TestActors }
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object RemoteActorSelectionSpec {
  final case class ActorSelReq(s: String)

  class SelectionActor extends Actor {
    def receive = {
      // if we get props and a name, create a child, send ref back
      case (p: Props, n: String) ⇒ sender() ! context.actorOf(p, n)
      // or select actor from here
      case ActorSelReq(s)        ⇒ sender() ! context.actorSelection(s)
    }
  }
  def selectionActorProps = Props(new SelectionActor)
}

class RemoteActorSelectionSpec extends ArteryMultiNodeSpec with ImplicitSender {

  import RemoteActorSelectionSpec._

  val systemB = system

  val systemA = {
    val remotePort = port(systemB)
    val remoteSysName = systemB.name

    val localSysName = "local-" + remoteSysName
    val localPort = SocketUtil.temporaryServerAddress(udp = true).getPort

    // nesting the hierarchy across the two systems
    newRemoteSystem(Some(s"""
      akka {
        remote.artery.port = $localPort
        actor.deployment {
          /looker2/child.remote = "artery://$remoteSysName@localhost:$remotePort"
          /looker2/child/grandchild.remote = "artery://$localSysName@localhost:$localPort"
        }
      }
    """))
  }

  "Remote actor selection" should {

    // TODO would like to split up in smaller cases but find it hard
    // TODO fails with "received Supervise from unregistered child" when looker2/child is created - akka/akka#20715
    "select actors across node boundaries" ignore {

      val localLooker2 = systemA.actorOf(selectionActorProps, "looker2")

      // child is configured to be deployed on remoteSystem
      localLooker2 ! ((selectionActorProps, "child"))
      val remoteChild = expectMsgType[ActorRef]

      // grandchild is configured to be deployed on local system but from remote system
      remoteChild ! ((selectionActorProps, "grandchild"))
      val localGrandchild = expectMsgType[ActorRef]
      localGrandchild.asInstanceOf[ActorRefScope].isLocal should ===(true)
      localGrandchild ! 53
      expectMsg(53)

      val localGrandchildSelection = systemA.actorSelection(system / "looker2" / "child" / "grandchild")
      localGrandchildSelection ! 54
      expectMsg(54)
      lastSender should ===(localGrandchild)
      lastSender should be theSameInstanceAs localGrandchild
      localGrandchildSelection ! Identify(localGrandchildSelection)
      val grandchild2 = expectMsgType[ActorIdentity].ref
      grandchild2 should ===(Some(localGrandchild))

      systemA.actorSelection("/user/looker2/child") ! Identify(None)
      expectMsgType[ActorIdentity].ref should ===(Some(remoteChild))

      localLooker2 ! ActorSelReq("child/..")
      expectMsgType[ActorSelection] ! Identify(None)
      expectMsgType[ActorIdentity].ref.get should be theSameInstanceAs localLooker2

      system.actorSelection(system / "looker2" / "child") ! ActorSelReq("..")
      expectMsgType[ActorSelection] ! Identify(None)
      expectMsgType[ActorIdentity].ref.get should be theSameInstanceAs localLooker2

      localGrandchild ! ((TestActors.echoActorProps, "grandgrandchild"))
      val grandgrandchild = expectMsgType[ActorRef]

      system.actorSelection("/user/looker2/child") ! Identify("idReq1")
      expectMsg(ActorIdentity("idReq1", Some(remoteChild)))
      system.actorSelection(remoteChild.path) ! Identify("idReq2")
      expectMsg(ActorIdentity("idReq2", Some(remoteChild)))
      system.actorSelection("/user/looker2/*") ! Identify("idReq3")
      expectMsg(ActorIdentity("idReq3", Some(remoteChild)))

      system.actorSelection("/user/looker2/child/grandchild") ! Identify("idReq4")
      expectMsg(ActorIdentity("idReq4", Some(localGrandchild)))
      system.actorSelection(remoteChild.path / "grandchild") ! Identify("idReq5")
      expectMsg(ActorIdentity("idReq5", Some(localGrandchild)))
      system.actorSelection("/user/looker2/*/grandchild") ! Identify("idReq6")
      expectMsg(ActorIdentity("idReq6", Some(localGrandchild)))
      system.actorSelection("/user/looker2/child/*") ! Identify("idReq7")
      expectMsg(ActorIdentity("idReq7", Some(localGrandchild)))
      system.actorSelection(remoteChild.path / "*") ! Identify("idReq8")
      expectMsg(ActorIdentity("idReq8", Some(localGrandchild)))

      system.actorSelection("/user/looker2/child/grandchild/grandgrandchild") ! Identify("idReq9")
      expectMsg(ActorIdentity("idReq9", Some(grandgrandchild)))
      system.actorSelection(remoteChild.path / "grandchild" / "grandgrandchild") ! Identify("idReq10")
      expectMsg(ActorIdentity("idReq10", Some(grandgrandchild)))
      system.actorSelection("/user/looker2/child/*/grandgrandchild") ! Identify("idReq11")
      expectMsg(ActorIdentity("idReq11", Some(grandgrandchild)))
      system.actorSelection("/user/looker2/child/*/*") ! Identify("idReq12")
      expectMsg(ActorIdentity("idReq12", Some(grandgrandchild)))
      system.actorSelection(remoteChild.path / "*" / "grandgrandchild") ! Identify("idReq13")
      expectMsg(ActorIdentity("idReq13", Some(grandgrandchild)))

      val sel1 = system.actorSelection("/user/looker2/child/grandchild/grandgrandchild")
      system.actorSelection(sel1.toSerializationFormat) ! Identify("idReq18")
      expectMsg(ActorIdentity("idReq18", Some(grandgrandchild)))

      remoteChild ! Identify("idReq14")
      expectMsg(ActorIdentity("idReq14", Some(remoteChild)))
      watch(remoteChild)
      remoteChild ! PoisonPill
      expectMsg("postStop")
      expectMsgType[Terminated].actor should ===(remoteChild)
      localLooker2 ! ((TestActors.echoActorProps, "child"))
      val child2 = expectMsgType[ActorRef]
      child2 ! Identify("idReq15")
      expectMsg(ActorIdentity("idReq15", Some(child2)))
      system.actorSelection(remoteChild.path) ! Identify("idReq16")
      expectMsg(ActorIdentity("idReq16", Some(child2)))
      remoteChild ! Identify("idReq17")
      expectMsg(ActorIdentity("idReq17", None))

      child2 ! 55
      expectMsg(55)
      // msg to old ActorRef (different uid) should not get through
      child2.path.uid should not be (remoteChild.path.uid)
      remoteChild ! 56
      expectNoMsg(1.second)
      system.actorSelection(system / "looker2" / "child") ! 57
      expectMsg(57)
    }

  }

}
