/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.actor.ActorSystem
import akka.http.Http
import akka.http.model.HttpEntity
import akka.http.model.Multipart.FormData
import akka.http.unmarshalling.Unmarshal
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.TestKit
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import java.io.{ OutputStream, BufferedOutputStream, FileOutputStream, File }
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import org.scalatest.concurrent.Timeouts
import org.scalatest.time.{ Milliseconds, Span }
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.sys.process._
import scala.util.Random

object MultipartFormDataSpec {

  val debug = true

  def temporaryServerAddress(interface: String = "127.0.0.1"): InetSocketAddress = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.socket.bind(new InetSocketAddress(interface, 0))
      val port = serverSocket.socket.getLocalPort
      new InetSocketAddress(interface, port)
    } finally serverSocket.close()
  }

  def temporaryServerAddressAndPort(interface: String = "127.0.0.1"): (String, Int) = {
    val socketAddress = temporaryServerAddress(interface)
    socketAddress.getAddress.getHostAddress -> socketAddress.getPort
  }

  def withHttpServer(route: Route)(block: (String, Int) ⇒ Unit)(
    implicit system: ActorSystem, materializer: FlowMaterializer, setup: RoutingSetup): Unit = {
    val (address, port) = temporaryServerAddressAndPort()
    val binding = Http().bind(address, port)
    val materializedMap = binding.startHandlingWith(route)
    val localAddress = Await.result(binding.localAddress(materializedMap), 5 seconds)
    try {
      block(address, port)
    } finally {
      binding.unbind(materializedMap)
    }
  }

  val namePartName = "whatIsYourName"
  val questPartName = "whatIsYourQuest"
  val colorPartName = "whatIsYourFavouriteColor"
  val blobPartName = "theBlob"
  val conversationPartNames = Set(namePartName, questPartName, colorPartName)

  case class ConversationAnswers(name: String, quest: String, color: String)
  case class BlobPart(filename: String, source: Source[ByteString])

  private def conversationAnswersAndRest(bodyParts: Source[FormData.BodyPart])(
    implicit mat: FlowMaterializer, ec: ExecutionContext): Future[(ConversationAnswers, Source[FormData.BodyPart])] = {
    bodyParts
      .prefixAndTail(conversationPartNames.size)
      .runWith(Sink.head)
      .flatMap {
        case (conversationParts, otherParts) ⇒
          val requirementPartsMappings = conversationParts.map { conversationPart ⇒
            Unmarshal(conversationPart.entity)
              .to[String]
              .map(conversationPart.name -> _)
          }
          Future.sequence(requirementPartsMappings).flatMap {
            _.toMap match {
              case mappings ⇒
                val relevantPartNames = mappings.keySet.intersect(conversationPartNames)
                val missingPartNames = conversationPartNames.diff(relevantPartNames)
                if (missingPartNames.isEmpty) {
                  val answers = ConversationAnswers(
                    mappings(namePartName),
                    mappings(questPartName),
                    mappings(colorPartName))
                  Future.successful(answers -> otherParts)
                } else
                  Future.failed(new IllegalArgumentException(s"Missing field(s): ${missingPartNames.mkString(", ")}"))
            }
          }
      }
  }

  def readBlob(bodyParts: Source[FormData.BodyPart])(implicit mat: FlowMaterializer, ec: ExecutionContext): Future[String] = {
    def readBlob(blob: BlobPart): Long = {
      Await.result(blob.source.fold(0L) {
        case (sum, bytes) ⇒
          val newSum = sum + bytes.size
          if (debug) println(s"Total $newSum after reading ${bytes.size}")
          newSum
      }, maxDuration)
    }

    val blobParts = bodyParts.collect {
      case part @ FormData.BodyPart(name, entitiy, _, _) if name == blobPartName && part.filename.isDefined ⇒
        BlobPart(part.filename.get, entitiy.dataBytes)
    }
    blobParts.map(readBlob).map(_.toString).runWith(Sink.head)
  }

  def createBlobOfSize(size: Long): String = {
    val bytesSize: Int = 1024
    val bytes = Array.ofDim[Byte](bytesSize)
    @tailrec
    def writeRandomBytes(random: Random, remaining: Long, output: OutputStream): Unit = remaining match {
      case r if r > 0 ⇒
        random.nextBytes(bytes)
        val writeSize = if (r > bytesSize) bytesSize else r.toInt
        output.write(bytes, 0, writeSize)
        writeRandomBytes(random, r - writeSize, output)
      case r if r == 0 ⇒ // done
      case r           ⇒ throw new IllegalArgumentException(s"Cant write less than 0 bytes [$remaining]")
    }
    val rnd = new Random(size)
    val name = rnd.nextLong.toHexString
    val temp = File.createTempFile(name, ".tmp")
    val out = new BufferedOutputStream(new FileOutputStream(temp))
    try {
      writeRandomBytes(rnd, size, out)
    } finally {
      out.close()
    }
    temp.getCanonicalPath
  }

  val testConfig: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")

  val maxDuration = 10 seconds
}

class MultipartFormDataSpec extends WordSpecLike with Matchers with BeforeAndAfterAll with Directives with Timeouts {

  import MultipartFormDataSpec._

  implicit val system = ActorSystem(getClass.getSimpleName, testConfig)

  var blobPath: String = ""

  override def beforeAll {
    blobPath = createBlobOfSize(1024 * 1024 * 2.3 toLong)
  }

  final override def afterAll {
    new File(blobPath).delete()
    TestKit.shutdownActorSystem(system)
  }

  import system.dispatcher

  implicit val materializer = FlowMaterializer()

  "An HTTP Server" should {

    "Accept a simple large multipart form upload" in {
      withHttpServer(
        // format: OFF
        path("form") {
          post {
            extractRequest { request =>
              complete(loadSimpleForm(request.entity))
            }
          }
          // format: ON
        }) { (address, port) ⇒
          failAfter(Span(maxDuration.toMillis, Milliseconds)) {
            val command = List(
              "curl",
              "-v", // verbose
              "-H", "Expect:", // remove Expect: 100-continue
              "--form", s"""$blobPartName=@"$blobPath"""",
              s"http://$address:$port/form")
            if (debug) println(s"About to execute: ${command.mkString(" ")}")
            command.! should be(0)
          }
        }
    }

    "Accept a complex large multipart form upload" ignore {
      withHttpServer(
        // format: OFF
        path("form") {
          post {
            extractRequest { request =>
              complete(loadForm(request.entity))
            }
          }
          // format: ON
        }) { (address, port) ⇒
          failAfter(Span(maxDuration.toMillis, Milliseconds)) {
            val command = List(
              "curl",
              "-v", // verbose
              "-H", "Expect:", // remove Expect: 100-continue
              "--form", s"""$namePartName="My name is Sir Lancelot of Camelot"""",
              "--form", s"""$questPartName="To seek the Holy Grail"""",
              "--form", s"""$colorPartName="Blue"""",
              "--form", s"""$blobPartName=@"$blobPath"""",
              s"http://$address:$port/form")
            if (debug) println(s"About to execute: ${command.mkString(" ")}")
            command.! should be(0)
          }
        }
    }
  }

  private def loadForm(entity: HttpEntity): Future[String] =
    for {
      multiPartFormData ← Unmarshal(entity).to[FormData]
      (answers, rest) ← conversationAnswersAndRest(multiPartFormData.parts)
      blobResult ← readBlob(rest)
    } yield answers.toString + "\n" + blobResult

  private def loadSimpleForm(entity: HttpEntity): Future[String] =
    for {
      multiPartFormData ← Unmarshal(entity).to[FormData]
      blobResult ← readBlob(multiPartFormData.parts)
    } yield "Read the blob " + blobResult
}
