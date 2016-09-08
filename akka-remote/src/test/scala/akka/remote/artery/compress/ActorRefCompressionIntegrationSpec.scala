/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor._
import akka.pattern.ask
import akka.remote.artery.compress.CompressionProtocol.Events
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.{ Eventually, PatienceConfiguration }

import scala.concurrent.Await
import scala.concurrent.duration._

class ActorRefCompressionIntegrationSpec extends AkkaSpec(CompressionIntegrationSpec.commonConfig)
  with ImplicitSender with BeforeAndAfter with Eventually {
  import CompressionIntegrationSpec._

  implicit val t = Timeout(3.seconds)
  var systemB = ActorSystem("systemB", configB)

  "Outgoing ActorRef compression table" must {
    "compress chatty actor" in {
      val messagesToExchange = 10

      // listen for compression table events
      val aProbe = TestProbe()
      val b1Probe = TestProbe()
      system.eventStream.subscribe(aProbe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])
      systemB.eventStream.subscribe(b1Probe.ref, classOf[CompressionProtocol.Events.ReceivedActorRefCompressionTable])

      def voidSel = system.actorSelection(s"akka://systemB@localhost:$portB/user/void")
      systemB.actorOf(TestActors.blackholeProps, "void")

      // cause testActor-1 to become a heavy hitter
      (1 to messagesToExchange).foreach { i ⇒ voidSel ! "hello" } // does not reply, but a hot receiver should be advertised

      val a1 = aProbe.expectMsgType[Events.ReceivedActorRefCompressionTable](10.seconds)
      info("System [A] received: " + a1)
      assertCompression[ActorRef](a1.table, 0, _ should ===(system.deadLetters))
      assertCompression[ActorRef](a1.table, 1, _ should ===(testActor))
    }
  }

  def assertCompression[T](table: CompressionTable[T], id: Int, assertion: T ⇒ Unit): Unit = {
    table.map.find(_._2 == id)
      .orElse { throw new AssertionError(s"No key was compressed to the id [$id]! Table was: $table") }
      .foreach(i ⇒ assertion(i._1))
  }

  def identify(_system: String, port: Int, name: String): ActorRef = {
    val selection = system.actorSelection(s"akka://${_system}@localhost:$port/user/$name").resolveOne(3.seconds)
    Await.result(selection, 4.seconds)
  }

  override def afterTermination(): Unit =
    shutdownAllActorSystems()

  private def shutdownAllActorSystems(): Unit = {
    if (systemB != null) shutdown(systemB)
  }
}
