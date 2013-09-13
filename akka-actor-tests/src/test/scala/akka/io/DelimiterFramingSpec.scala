/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.actor.{ Props, ActorLogging, Actor }
import akka.TestUtils
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import akka.io.TcpPipelineHandler.{ Read, Management }
import akka.actor.ActorRef
import akka.actor.Deploy

object DelimiterFramingSpec {
  case class Listener(ref: ActorRef)
}

class DelimiterFramingSpec extends AkkaSpec("akka.actor.serialize-creators = on") {
  import DelimiterFramingSpec._

  val addresses = TestUtils.temporaryServerAddresses(4)

  "DelimiterFramingSpec" must {

    "send and receive delimiter based frames correctly (one byte delimiter, exclude)" in {
      testSetup(serverAddress = addresses(0), delimiter = "\n", includeDelimiter = false)
    }

    "send and receive delimiter based frames correctly (multi-byte delimiter, exclude)" in {
      testSetup(serverAddress = addresses(1), delimiter = "DELIMITER", includeDelimiter = false)
    }

    "send and receive delimiter based frames correctly (one byte delimiter, include)" in {
      testSetup(serverAddress = addresses(2), delimiter = "\n", includeDelimiter = true)
    }

    "send and receive delimiter based frames correctly (multi-byte delimiter, include)" in {
      testSetup(serverAddress = addresses(3), delimiter = "DELIMITER", includeDelimiter = true)
    }

  }

  val counter = new AtomicInteger

  def testSetup(serverAddress: InetSocketAddress, delimiter: String, includeDelimiter: Boolean): Unit = {
    val bindHandler = system.actorOf(Props(classOf[AkkaLineEchoServer], this, delimiter, includeDelimiter).withDeploy(Deploy.local))
    val probe = TestProbe()
    probe.send(IO(Tcp), Tcp.Bind(bindHandler, serverAddress))
    probe.expectMsgType[Tcp.Bound]
    bindHandler ! Listener(probe.lastSender)

    val client = new AkkaLineClient(serverAddress, delimiter, includeDelimiter)
    client.run()
    client.close()
  }

  class AkkaLineClient(address: InetSocketAddress, delimiter: String, includeDelimiter: Boolean) {

    val expectedDelimiter = if (includeDelimiter) delimiter else ""

    val probe = TestProbe()
    probe.send(IO(Tcp), Tcp.Connect(address))

    val connected = probe.expectMsgType[Tcp.Connected]
    val connection = probe.sender

    val init = TcpPipelineHandler.withLogger(system.log,
      new StringByteStringAdapter >>
        new DelimiterFraming(maxSize = 1024, delimiter = ByteString(delimiter), includeDelimiter = includeDelimiter) >>
        new TcpReadWriteAdapter)

    import init._

    val handler = system.actorOf(TcpPipelineHandler.props(init, connection, probe.ref).withDeploy(Deploy.local),
      "client" + counter.incrementAndGet())
    probe.send(connection, Tcp.Register(handler))

    def writeString(s: String): Unit = probe.send(handler, Command(s))
    def readString(): String = {
      probe.send(handler, Read(1))
      probe.expectMsgType[Event].evt
    }

    def writeReadAndVerify(req: String, expectedResponse: String): Unit = {
      writeString(req)
      readString() must be(expectedResponse)
    }

    def run() {
      writeReadAndVerify(s"testone$delimiter", s"testone$expectedDelimiter")
      writeReadAndVerify(s"two${delimiter}thr", s"two$expectedDelimiter")
      probe.expectNoMsg(1.seconds)
      writeReadAndVerify(s"ee$delimiter", s"three$expectedDelimiter")
      if (delimiter.size > 1) {
        val (first, second) = delimiter.splitAt(1)

        // Test a fragmented delimiter
        writeString(s"four$first")
        probe.expectNoMsg(1.seconds)
        writeReadAndVerify(second, s"four$expectedDelimiter")

        // Test cases of false match on a delimiter fragment
        for (piece ← s"${first}five${first}$delimiter") {
          probe.expectNoMsg(100.milliseconds)
          writeString(String.valueOf(piece))
        }
        readString() must be(s"${first}five${first}$expectedDelimiter")

      }
      writeString(s"${delimiter}${delimiter}")
      readString must be(expectedDelimiter)
      readString must be(expectedDelimiter)
    }

    def close() {
      probe.send(handler, Management(Tcp.Close))
      probe.expectMsgType[Tcp.ConnectionClosed]
      TestUtils.verifyActorTermination(handler)
    }
  }

  class AkkaLineEchoServer(delimiter: String, includeDelimiter: Boolean) extends Actor with ActorLogging {

    import Tcp.Connected

    var listener: ActorRef = _

    def receive: Receive = {
      case Listener(ref) ⇒ listener = ref
      case Connected(remote, _) ⇒
        val init =
          TcpPipelineHandler.withLogger(log,
            new StringByteStringAdapter >>
              new DelimiterFraming(maxSize = 1024, delimiter = ByteString(delimiter), includeDelimiter = includeDelimiter) >>
              new TcpReadWriteAdapter)
        import init._

        val connection = sender
        val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local), "pipeline")

        def readLine(): Unit = handler ! Read(1)

        connection ! Tcp.Register(handler)
        readLine()

        context become {
          case Event(data) ⇒
            if (includeDelimiter) sender ! Command(data)
            else sender ! Command(data + delimiter)
            readLine()
          case Tcp.PeerClosed ⇒ listener ! Tcp.Unbind
          case Tcp.Unbound    ⇒ context.stop(self)
        }
    }
  }

}
