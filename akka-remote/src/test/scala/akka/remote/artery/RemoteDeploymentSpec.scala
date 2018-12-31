/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import akka.remote.RARP

object RemoteDeploymentSpec {
  class Echo1 extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case ex: Exception ⇒ throw ex
      case x             ⇒ target = sender(); sender() ! x
    }

    override def preStart(): Unit = {}
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable): Unit = {}
    override def postStop(): Unit = {
      target ! "postStop"
    }
  }

  def parentProps(probe: ActorRef): Props =
    Props(new Parent(probe))

  class Parent(probe: ActorRef) extends Actor {
    var target: ActorRef = context.system.deadLetters

    override val supervisorStrategy = OneForOneStrategy() {
      case e: Exception ⇒
        probe ! e
        SupervisorStrategy.stop
    }

    def receive = {
      case p: Props ⇒
        sender() ! context.actorOf(p)

      case (p: Props, numMessages: Int) ⇒
        val child = context.actorOf(p)
        sender() ! child
        // stress as quick send as possible
        (0 until numMessages).foreach(n ⇒ child.tell(n, sender()))
    }
  }

  class DeadOnArrival extends Actor {
    throw new Exception("init-crash")

    def receive = Actor.emptyBehavior
  }
}

class RemoteDeploymentSpec extends ArteryMultiNodeSpec(
  ConfigFactory.parseString("""
    akka.remote.artery.advanced.inbound-lanes = 10
    akka.remote.artery.advanced.outbound-lanes = 3
    """).withFallback(ArterySpecSupport.defaultConfig)) {

  import RemoteDeploymentSpec._

  val port = RARP(system).provider.getDefaultAddress.port.get
  val conf =
    s"""
    akka.actor.deployment {
      /blub.remote = "akka://${system.name}@localhost:$port"
      "/parent*/*".remote = "akka://${system.name}@localhost:$port"
    }
    akka.remote.artery.advanced.inbound-lanes = 10
    akka.remote.artery.advanced.outbound-lanes = 3
    """

  val masterSystem = newRemoteSystem(name = Some("Master" + system.name), extraConfig = Some(conf))
  val masterPort = address(masterSystem).port.get

  "Remoting" must {

    "create and supervise children on remote node" in {
      val senderProbe = TestProbe()(masterSystem)
      val r = masterSystem.actorOf(Props[Echo1], "blub")
      r.path.toString should ===(s"akka://${system.name}@localhost:${port}/remote/akka/${masterSystem.name}@localhost:${masterPort}/user/blub")

      r.tell(42, senderProbe.ref)
      senderProbe.expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(masterSystem)
      senderProbe.expectMsg("preRestart")
      r.tell(43, senderProbe.ref)
      senderProbe.expectMsg(43)
      system.stop(r)
      senderProbe.expectMsg("postStop")
    }

    "notice immediate death" in {
      val parent = masterSystem.actorOf(parentProps(testActor), "parent")
      EventFilter[ActorInitializationException](occurrences = 1).intercept {
        parent.tell(Props[DeadOnArrival], testActor)
        val child = expectMsgType[ActorRef]
        expectMsgType[ActorInitializationException]

        watch(child)
        expectTerminated(child)
      }(masterSystem)
    }

    "deliver all messages" in {
      val numParents = 10
      val numChildren = 20
      val numMessages = 5

      val parents = (0 until numParents).map { i ⇒
        masterSystem.actorOf(parentProps(testActor), s"parent-$i")
      }.toVector

      val probes = Vector.fill(numParents, numChildren)(TestProbe()(masterSystem))
      val childProps = Props[Echo1]
      for (p ← (0 until numParents); c ← (0 until numChildren)) {
        parents(p).tell((childProps, numMessages), probes(p)(c).ref)
      }

      for (p ← (0 until numParents); c ← (0 until numChildren)) {
        val probe = probes(p)(c)
        probe.expectMsgType[ActorRef] // the child
      }

      val expectedMessages = (0 until numMessages).toVector
      for (p ← (0 until numParents); c ← (0 until numChildren)) {
        val probe = probes(p)(c)
        probe.receiveN(numMessages) should equal(expectedMessages)
      }

    }

  }

}
