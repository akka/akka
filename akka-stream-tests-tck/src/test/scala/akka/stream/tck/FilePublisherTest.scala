/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import java.io.{ File, FileWriter }
import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.scaladsl.FileIO
import akka.stream.scaladsl.{ Sink }
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.{ EventFilter, TestEvent }
import akka.util.ByteString
import org.reactivestreams.Publisher
import org.testng.annotations.{ AfterClass, BeforeClass }
import akka.testkit.AkkaSpec

class FilePublisherTest extends AkkaPublisherVerification[ByteString] {

  val ChunkSize = 256
  val Elements = 1000

  @BeforeClass
  override def createActorSystem(): Unit = {
    _system = ActorSystem(Logging.simpleName(getClass), UnboundedMailboxConfig.withFallback(AkkaSpec.testConf))
    _system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))
  }

  val file = {
    val f = File.createTempFile("file-source-tck", ".tmp")
    val chunk = "x" * ChunkSize
    val fw = new FileWriter(f)
    for (i ← 1 to Elements) fw.append(chunk)
    fw.close()
    f
  }

  def createPublisher(elements: Long): Publisher[ByteString] =
    FileIO.fromFile(file, chunkSize = 512)
      .take(elements)
      .runWith(Sink.asPublisher(false))

  @AfterClass
  def after = file.delete()

  override def maxElementsFromPublisher(): Long = Elements
}
