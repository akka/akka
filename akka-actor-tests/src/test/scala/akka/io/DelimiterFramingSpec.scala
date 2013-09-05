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
import akka.io.TcpPipelineHandler.Management
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

    def run() {
      probe.send(handler, Command(s"testone$delimiter"))
      probe.expectMsg(Event(s"testone$expectedDelimiter"))
      probe.send(handler, Command(s"two${delimiter}thr"))
      probe.expectMsg(Event(s"two$expectedDelimiter"))
      probe.expectNoMsg(1.seconds)
      probe.send(handler, Command(s"ee$delimiter"))
      probe.expectMsg(Event(s"three$expectedDelimiter"))
      if (delimiter.size > 1) {
        val (first, second) = delimiter.splitAt(1)

        // Test a fragmented delimiter
        probe.send(handler, Command(s"four$first"))
        probe.expectNoMsg(1.seconds)
        probe.send(handler, Command(second))
        probe.expectMsg(Event(s"four$expectedDelimiter"))

        // Test cases of false match on a delimiter fragment
        for (piece ← s"${first}five${first}$delimiter") {
          probe.expectNoMsg(100.milliseconds)
          probe.send(handler, Command(String.valueOf(piece)))
        }
        probe.expectMsg(Event(s"${first}five${first}$expectedDelimiter"))

      }
      probe.send(handler, Command(s"${delimiter}${delimiter}"))
      probe.expectMsg(Event(expectedDelimiter))
      probe.expectMsg(Event(expectedDelimiter))
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
      case Connected(remote, _, _) ⇒
        val init =
          TcpPipelineHandler.withLogger(log,
            new StringByteStringAdapter >>
              new DelimiterFraming(maxSize = 1024, delimiter = ByteString(delimiter), includeDelimiter = includeDelimiter) >>
              new TcpReadWriteAdapter)
        import init._

        val connection = sender
        val handler = context.actorOf(TcpPipelineHandler.props(init, sender, self).withDeploy(Deploy.local), "pipeline")

        connection ! Tcp.Register(handler)

        context become {
          case Event(data) ⇒
            if (includeDelimiter) sender ! Command(data)
            else sender ! Command(data + delimiter)
          case Tcp.PeerClosed ⇒ listener ! Tcp.Unbind
          case Tcp.Unbound    ⇒ context.stop(self)
        }
    }
  }

}
