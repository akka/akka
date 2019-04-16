/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.Terminated

import language.postfixOps
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.unused
import testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class NewRemoteActorMultiJvmSpec(artery: Boolean) extends MultiNodeConfig {

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.artery.enabled = $artery
      """).withFallback(RemotingMultiNodeSpec.commonConfig)))

  val master = role("master")
  val slave = role("slave")

  deployOn(master, """
    /service-hello.remote = "@slave@"
    /service-hello-null.remote = "@slave@"
    /service-hello3.remote = "@slave@"
    """)

  deployOnAll("""/service-hello2.remote = "@slave@" """)
}

class NewRemoteActorMultiJvmNode1 extends NewRemoteActorSpec(new NewRemoteActorMultiJvmSpec(artery = false))
class NewRemoteActorMultiJvmNode2 extends NewRemoteActorSpec(new NewRemoteActorMultiJvmSpec(artery = false))

class ArteryNewRemoteActorMultiJvmNode1 extends NewRemoteActorSpec(new NewRemoteActorMultiJvmSpec(artery = true))
class ArteryNewRemoteActorMultiJvmNode2 extends NewRemoteActorSpec(new NewRemoteActorMultiJvmSpec(artery = true))

object NewRemoteActorSpec {
  class SomeActor extends Actor {
    def receive = {
      case "identify" => sender() ! self
    }
  }

  class SomeActorWithParam(@unused ignored: String) extends Actor {
    def receive = {
      case "identify" => sender() ! self
    }
  }
}

abstract class NewRemoteActorSpec(multiNodeConfig: NewRemoteActorMultiJvmSpec)
    extends RemotingMultiNodeSpec(multiNodeConfig) {
  import multiNodeConfig._
  import NewRemoteActorSpec._

  def initialParticipants = roles.size

  // ensure that system.terminate is successful
  override def verifySystemShutdown = true

  "A new remote actor" must {
    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef" in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello")
        actor.isInstanceOf[RemoteActorRef] should ===(true)
        actor.path.address should ===(node(slave).address)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        expectMsgType[ActorRef].path.address should ===(slaveAddress)
      }

      enterBarrier("done")
    }

    "be locally instantiated on a remote node (with null parameter) and be able to communicate through its RemoteActorRef" in {

      runOn(master) {
        val actor = system.actorOf(Props(classOf[SomeActorWithParam], null), "service-hello-null")
        actor.isInstanceOf[RemoteActorRef] should ===(true)
        actor.path.address should ===(node(slave).address)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        expectMsgType[ActorRef].path.address should ===(slaveAddress)
      }

      enterBarrier("done")
    }

    "be locally instantiated on a remote node and be able to communicate through its RemoteActorRef (with deployOnAll)" in {

      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello2")
        actor.isInstanceOf[RemoteActorRef] should ===(true)
        actor.path.address should ===(node(slave).address)

        val slaveAddress = testConductor.getAddressFor(slave).await
        actor ! "identify"
        expectMsgType[ActorRef].path.address should ===(slaveAddress)
      }

      enterBarrier("done")
    }

    "be able to shutdown system when using remote deployed actor" in within(20 seconds) {
      runOn(master) {
        val actor = system.actorOf(Props[SomeActor], "service-hello3")
        actor.isInstanceOf[RemoteActorRef] should ===(true)
        actor.path.address should ===(node(slave).address)
        // This watch is in race with the shutdown of the watched system. This race should remain, as the test should
        // handle both cases:
        //  - remote system receives watch, replies with DeathWatchNotification
        //  - remote system never gets watch, but DeathWatch heartbeats time out, and AddressTerminated is generated
        //    (this needs some time to happen)
        watch(actor)

        enterBarrier("deployed")

        // master system is supposed to be shutdown after slave
        // this should be triggered by slave system.terminate
        expectMsgPF() { case Terminated(`actor`) => true }
      }

      runOn(slave) {
        enterBarrier("deployed")
      }

      // Important that this is the last test.
      // It should not be any barriers here.
      // verifySystemShutdown = true will ensure that system.terminate is successful
    }
  }
}
