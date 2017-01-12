/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.pattern.ask
import akka.remote.artery.compress.CompressionProtocol.Events
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import akka.remote.artery.ArteryMultiNodeSpec

object CompressionIntegrationSpec {

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       loglevel = INFO

       actor {
         serializers {
           test-message = "akka.remote.artery.compress.TestMessageSerializer"
         }
         serialization-bindings {
           "akka.remote.artery.compress.TestMessage" = test-message
         }
       }

       remote.artery.advanced.compression {
         actor-refs.advertisement-interval = 2 seconds
         manifests.advertisement-interval = 2 seconds
       }
     }
  """)

}

class CompressionIntegrationSpec extends ArteryMultiNodeSpec(CompressionIntegrationSpec.commonConfig)
  with ImplicitSender {
  import CompressionIntegrationSpec._

  val systemB = newRemoteSystem(name = Some("systemB"))
  val messagesToExchange = 10

  "Compression table" must {
    "be advertised for chatty ActorRef and manifest" in {
      // listen for compression table events
      val aManifestProbe = TestProbe()(system)
      val bManifestProbe = TestProbe()(systemB)
      system.eventStream.subscribe(aManifestProbe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
      systemB.eventStream.subscribe(bManifestProbe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
      val aRefProbe = TestProbe()(system)
      val bRefProbe = TestProbe()(systemB)
      system.eventStream.subscribe(aRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])
      systemB.eventStream.subscribe(bRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

      val echoRefB = systemB.actorOf(TestActors.echoActorProps, "echo")

      system.actorSelection(rootActorPath(systemB) / "user" / "echo") ! Identify(None)
      val echoRefA = expectMsgType[ActorIdentity].ref.get

      // cause TestMessage manifest to become a heavy hitter
      // cause echo to become a heavy hitter
      (1 to messagesToExchange).foreach { i ⇒ echoRefA ! TestMessage("hello") }
      receiveN(messagesToExchange) // the replies

      within(10.seconds) {
        // on system A side
        awaitAssert {
          val a1 = aManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
          info("System [A] received: " + a1)
          a1.table.version.toInt should be >= (1)
          a1.table.dictionary.keySet should contain("TestMessageManifest")
        }
        awaitAssert {
          val a1 = aRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
          info("System [A] received: " + a1)
          a1.table.version.toInt should be >= (1)
          a1.table.dictionary.keySet should contain(echoRefA) // recipient
          a1.table.dictionary.keySet should contain(testActor) // sender
        }

        // on system B side
        awaitAssert {
          val b1 = bManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
          info("System [B] received: " + b1)
          b1.table.version.toInt should be >= (1)
          b1.table.dictionary.keySet should contain("TestMessageManifest")
        }
        awaitAssert {
          val b1 = bRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
          info("System [B] received: " + b1)
          b1.table.version.toInt should be >= (1)
          b1.table.dictionary.keySet should contain(echoRefB)
        }
      }

      // and if we continue sending new advertisements with higher version number are advertised
      within(20.seconds) {
        val ignore = TestProbe()(system)
        awaitAssert {
          echoRefA.tell(TestMessage("hello2"), ignore.ref)
          val a2 = aManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
          info("System [A] received more: " + a2)
          a2.table.version.toInt should be >= (3)
        }
        awaitAssert {
          echoRefA.tell(TestMessage("hello2"), ignore.ref)
          val a2 = aRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
          info("System [A] received more: " + a2)
          a2.table.version.toInt should be >= (3)
        }

        awaitAssert {
          echoRefA.tell(TestMessage("hello3"), ignore.ref)
          val b2 = bManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
          info("System [B] received more: " + b2)
          b2.table.version.toInt should be >= (3)
        }
        awaitAssert {
          echoRefA.tell(TestMessage("hello3"), ignore.ref)
          val b2 = bRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
          info("System [B] received more: " + b2)
          b2.table.version.toInt should be >= (3)
        }
      }
    }

  }

  "handle noSender sender" in {
    val aRefProbe = TestProbe()(systemB)
    system.eventStream.subscribe(aRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

    val probeB = TestProbe()(systemB)
    systemB.actorOf(TestActors.forwardActorProps(probeB.ref), "fw1")

    system.actorSelection(rootActorPath(systemB) / "user" / "fw1") ! Identify(None)
    val fwRefA = expectMsgType[ActorIdentity].ref.get

    fwRefA.tell(TestMessage("hello-fw1-a"), ActorRef.noSender)
    probeB.expectMsg(TestMessage("hello-fw1-a"))

    within(10.seconds) {
      // on system A side
      awaitAssert {
        val a1 = aRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
        info("System [A] received: " + a1)
        a1.table.dictionary.keySet should contain(fwRefA) // recipient
        a1.table.dictionary.keySet should not contain (system.deadLetters) // sender
      }
    }

    fwRefA.tell(TestMessage("hello-fw1-b"), ActorRef.noSender)
    probeB.expectMsg(TestMessage("hello-fw1-b"))
  }

  "handle deadLetters sender" in {
    val aRefProbe = TestProbe()(systemB)
    system.eventStream.subscribe(aRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

    val probeB = TestProbe()(systemB)
    systemB.actorOf(TestActors.forwardActorProps(probeB.ref), "fw2")

    system.actorSelection(rootActorPath(systemB) / "user" / "fw2") ! Identify(None)
    val fwRefA = expectMsgType[ActorIdentity].ref.get

    fwRefA.tell(TestMessage("hello-fw2-a"), ActorRef.noSender)
    probeB.expectMsg(TestMessage("hello-fw2-a"))

    within(10.seconds) {
      // on system A side
      awaitAssert {
        val a1 = aRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
        info("System [A] received: " + a1)
        a1.table.dictionary.keySet should contain(fwRefA) // recipient
        a1.table.dictionary.keySet should not contain (system.deadLetters) // sender
      }
    }

    fwRefA.tell(TestMessage("hello-fw2-b"), ActorRef.noSender)
    probeB.expectMsg(TestMessage("hello-fw2-b"))
  }

  "work when starting new ActorSystem with same hostname:port" in {
    val port = address(systemB).port.get
    shutdown(systemB)
    val systemB2 = newRemoteSystem(
      extraConfig = Some(s"akka.remote.artery.canonical.port=$port"),
      name = Some("systemB"))

    // listen for compression table events
    val aManifestProbe = TestProbe()(system)
    val bManifestProbe = TestProbe()(systemB2)
    system.eventStream.subscribe(aManifestProbe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
    systemB2.eventStream.subscribe(bManifestProbe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
    val aRefProbe = TestProbe()(system)
    val bRefProbe = TestProbe()(systemB2)
    system.eventStream.subscribe(aRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])
    systemB2.eventStream.subscribe(bRefProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

    val echoRefB2 = systemB2.actorOf(TestActors.echoActorProps, "echo2")

    // messages to the new system might be dropped, before new handshake is completed
    within(5.seconds) {
      awaitAssert {
        val p = TestProbe()(system)
        system.actorSelection(rootActorPath(systemB2) / "user" / "echo2").tell(Identify(None), p.ref)
        p.expectMsgType[ActorIdentity](1.second).ref.get
      }
    }

    system.actorSelection(rootActorPath(systemB2) / "user" / "echo2") ! Identify(None)
    val echoRefA = expectMsgType[ActorIdentity].ref.get

    // cause TestMessage manifest to become a heavy hitter
    (1 to messagesToExchange).foreach { i ⇒ echoRefA ! TestMessage("hello") }
    receiveN(messagesToExchange) // the replies

    within(10.seconds) {
      // on system A side
      awaitAssert {
        val a2 = aManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
        info("System [A] received: " + a2)
        a2.table.version.toInt should be >= (1)
        a2.table.version.toInt should be < (3)
        a2.table.dictionary.keySet should contain("TestMessageManifest")
      }
      awaitAssert {
        val a2 = aRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
        info("System [A] received: " + a2)
        a2.table.version.toInt should be >= (1)
        a2.table.version.toInt should be < (3)
        a2.table.dictionary.keySet should contain(echoRefA) // recipient
        a2.table.dictionary.keySet should contain(testActor) // sender
      }

      // on system B2 side
      awaitAssert {
        val b2 = bManifestProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](2.seconds)
        info("System [B2] received: " + b2)
        b2.table.version.toInt should be >= (1)
        b2.table.dictionary.keySet should contain("TestMessageManifest")
      }
      awaitAssert {
        val b2 = bRefProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
        info("System [B] received: " + b2)
        b2.table.version.toInt should be >= (1)
        b2.table.dictionary.keySet should contain(echoRefB2)
      }
    }
  }

  "wrap around" in {
    val extraConfig = """
      akka.remote.artery.advanced.compression {
        actor-refs.advertisement-interval = 10 millis
      }
    """

    val systemWrap = newRemoteSystem(extraConfig = Some(extraConfig))

    val receivedActorRefCompressionTableProbe = TestProbe()(system)
    system.eventStream.subscribe(
      receivedActorRefCompressionTableProbe.ref,
      classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

    def createAndIdentify(i: Int) = {
      val echoWrap = systemWrap.actorOf(TestActors.echoActorProps, s"echo_$i")
      system.actorSelection(rootActorPath(systemWrap) / "user" / s"echo_$i") ! Identify(None)
      expectMsgType[ActorIdentity].ref.get
    }

    val maxTableVersions = 130 // so table version wraps around at least once
    var lastTableVersion = 0

    for (iteration ← 1 to maxTableVersions) {
      val echoWrap = createAndIdentify(iteration) // create a different actor for every iteration

      // cause echo to become a heavy hitter
      (1 to messagesToExchange).foreach { i ⇒ echoWrap ! TestMessage("hello") }
      receiveN(messagesToExchange) // the replies

      receivedActorRefCompressionTableProbe.within(5.seconds) {
        var currentTableVersion = -1
        // discard duplicates with awaitAssert until we receive next version
        receivedActorRefCompressionTableProbe.awaitAssert {
          val a1 = receivedActorRefCompressionTableProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](2.seconds)
          currentTableVersion = a1.table.version.toInt
          // until we get next version, discard duplicates
          currentTableVersion should !==(lastTableVersion)
        }
        lastTableVersion = currentTableVersion
        currentTableVersion should ===(iteration & 0x7F)
      }

    }
  }

}

final case class TestMessage(name: String)

class TestMessageSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest {

  val TestMessageManifest = "TestMessageManifest"

  override val identifier: Int = 101

  override def manifest(o: AnyRef): String =
    o match {
      case _: TestMessage ⇒ TestMessageManifest
    }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case msg: TestMessage ⇒ msg.name.getBytes
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case TestMessageManifest ⇒ TestMessage(new String(bytes))
      case unknown             ⇒ throw new Exception("Unknown manifest: " + unknown)
    }
  }
}
