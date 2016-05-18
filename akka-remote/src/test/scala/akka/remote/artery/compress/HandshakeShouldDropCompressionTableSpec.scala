/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ ActorIdentity, ActorSystem, Identify }
import akka.remote.artery.compress.CompressionProtocol.Events
import akka.testkit._
import akka.util.Timeout
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter

import scala.concurrent.Await
import scala.concurrent.duration._

object HandshakeShouldDropCompressionTableSpec {
  // need the port before systemB is started
  val portB = SocketUtil.temporaryServerAddress("localhost", udp = true).getPort

  val commonConfig = ConfigFactory.parseString(s"""
     akka {
       loglevel = INFO
        
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.advanced {
         compression.enabled = on
         compression.debug = on
       }
       remote.artery.hostname = localhost
       remote.artery.port = 0
       remote.handshake-timeout = 10s
       
     }
  """)

  val configB = ConfigFactory.parseString(s"akka.remote.artery.port = $portB")
    .withFallback(commonConfig)

}

class HandshakeShouldDropCompressionTableSpec extends AkkaSpec(HandshakeShouldDropCompressionTableSpec.commonConfig)
  with ImplicitSender with BeforeAndAfter {
  import HandshakeShouldDropCompressionTableSpec._

  implicit val t = Timeout(3.seconds)
  var systemB: ActorSystem = null

  before {
    systemB = ActorSystem("systemB", configB)
  }

  "Outgoing compression table" must {
    "be dropped on system restart" in {
      val messagesToExchange = 10

      // listen for compression table events
      val aProbe = TestProbe()
      val a1Probe = TestProbe()
      val aNew2Probe = TestProbe()
      val b1Probe = TestProbe()
      system.eventStream.subscribe(aProbe.ref, classOf[CompressionProtocol.Events.Event])
      systemB.eventStream.subscribe(b1Probe.ref, classOf[CompressionProtocol.Events.Event])

      def voidSel = system.actorSelection(s"artery://systemB@localhost:$portB/user/void")
      systemB.actorOf(TestActors.blackholeProps, "void")

      // cause testActor-1 to become a heavy hitter
      (1 to messagesToExchange).foreach { i ⇒ voidSel ! "hello" } // does not reply, but a hot receiver should be advertised
      // give it enough time to advertise first table
      val a0 = aProbe.expectMsgType[Events.ReceivedCompressionAdvertisement](10.seconds)
      info("System [A] received: " + a0)
      a0.id should ===(1)
      a0.key.toString should include(testActor.path.name)

      // cause a1Probe to become a heavy hitter (we want to not have it in the 2nd compression table later)
      (1 to messagesToExchange).foreach { i ⇒ voidSel.tell("hello", a1Probe.ref) } // does not reply, but a hot receiver should be advertised
      // give it enough time to advertise first table
      val a1 = aProbe.expectMsgType[Events.ReceivedCompressionAdvertisement](10.seconds)
      info("System [A] received: " + a1)
      a1.id should ===(2)
      a1.key.toString should include(a1Probe.ref.path.name)

      log.warning("SHUTTING DOWN system {}...", systemB)
      shutdown(systemB)
      systemB = ActorSystem("systemB", configB)
      Thread.sleep(5000)
      log.warning("SYSTEM READY {}...", systemB)

      systemB.actorOf(TestActors.blackholeProps, "void") // start it again
      (1 to messagesToExchange).foreach { i ⇒ voidSel ! "hello" } // does not reply, but a hot receiver should be advertised
      // compression triggered again
      val a2 = aProbe.expectMsgType[Events.ReceivedCompressionAdvertisement](10.seconds)
      info("System [A] received: " + a2)
      a2.id should ===(1)
      a2.key.toString should include(testActor.path.name)

      (1 to messagesToExchange).foreach { i ⇒ voidSel.tell("hello", aNew2Probe.ref) } // does not reply, but a hot receiver should be advertised
      // compression triggered again
      val a3 = aProbe.expectMsgType[Events.ReceivedCompressionAdvertisement](10.seconds)
      info("Received second compression: " + a3)
      a3.id should ===(2)
      a3.key.toString should include(aNew2Probe.ref.path.name)
    }

  }

  def identify(_system: String, port: Int, name: String) = {
    val selection =
      system.actorSelection(s"artery://${_system}@localhost:$port/user/$name")
    val ActorIdentity(1, ref) = Await.result(selection ? Identify(1), 3.seconds)
    ref.get
  }

  after {
    shutdownAllActorSystems()
  }

  override def afterTermination(): Unit =
    shutdownAllActorSystems()

  private def shutdownAllActorSystems(): Unit = {
    if (systemB != null) shutdown(systemB)
  }
}

