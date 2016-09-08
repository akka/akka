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

class ClassManifestCompressionIntegrationSpec extends AkkaSpec(CompressionIntegrationSpec.commonConfig)
  with ImplicitSender with BeforeAndAfter with Eventually {
  import CompressionIntegrationSpec._

  implicit val t = Timeout(3.seconds)
  var systemB = ActorSystem("systemB", configB)

  "Outgoing Manifest compression table" must {
    "compress chatty manifest" in {
      val messagesToExchange = 10

      // listen for compression table events
      val aProbe = TestProbe()
      val b1Probe = TestProbe()
      system.eventStream.subscribe(aProbe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
      systemB.eventStream.subscribe(b1Probe.ref, classOf[CompressionProtocol.Events.ReceivedClassManifestCompressionTable])
      systemB.actorOf(TestActors.blackholeProps, "void-2")

      Thread.sleep(1000)
      val voidRef = Await.result(system.actorSelection(s"akka://systemB@localhost:$portB/user/void-2").resolveOne(3.second), 3.seconds)

      // cause testActor-1 to become a heavy hitter
      (1 to messagesToExchange).foreach { i ⇒ voidRef ! TestMessage("hello") } // does not reply, but a hot receiver should be advertised

      eventually(PatienceConfiguration.Timeout(20.seconds)) {
        val a1 = aProbe.expectMsgType[Events.ReceivedClassManifestCompressionTable](10.seconds)
        info("System [A] received: " + a1)
        assertCompression[String](a1.table, 0, _ should ===("TestMessageManifest"))
      }
    }
  }

  def assertCompression[T](table: CompressionTable[T], id: Int, assertion: T ⇒ Unit): Unit = {
    table.map.find(_._2 == id)
      .orElse { throw new AssertionError(s"No key was compressed to the id [$id]! Table was: $table") }
      .foreach(i ⇒ assertion(i._1))
  }

  def identify(_system: String, port: Int, name: String) = {
    val selection =
      system.actorSelection(s"akka://${_system}@localhost:$port/user/$name")
    val ActorIdentity(1, ref) = Await.result(selection ? Identify(1), 3.seconds)
    ref.get
  }

  override def afterTermination(): Unit =
    shutdownAllActorSystems()

  private def shutdownAllActorSystems(): Unit = {
    if (systemB != null) shutdown(systemB)
  }
}

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest

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
