/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.testkit.{ TestProbe, AkkaSpec }
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.actor.{ Props, ActorLogging, Actor, ActorContext }
import akka.TestUtils
import java.util.concurrent.atomic.AtomicInteger

class DelimiterFramingSpec extends AkkaSpec {

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
    val bindHandler = system.actorOf(Props(classOf[AkkaLineEchoServer], this, delimiter, includeDelimiter))
    val probe = TestProbe()
    probe.send(IO(Tcp), Tcp.Bind(bindHandler, serverAddress))
    probe.expectMsgType[Tcp.Bound]

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

    val init = new TcpPipelineHandler.Init(
      new StringByteStringAdapter >>
        new DelimiterFraming(maxSize = 1024, delimiter = ByteString(delimiter), includeDelimiter = includeDelimiter) >>
        new TcpReadWriteAdapter) {
      override def makeContext(actorContext: ActorContext): HasLogging = new HasLogging {
        override def getLogger = system.log
      }
    }

    import init._

    val handler = system.actorOf(TcpPipelineHandler(init, connection, probe.ref),
      "client" + counter.incrementAndGet())
    probe.send(connection, Tcp.Register(handler))

    def run() {
      probe.send(handler, Command(s"testone$delimiter"))
      probe.expectMsg(Event(s"testone$expectedDelimiter"))
      probe.send(handler, Command(s"two${delimiter}thr"))
      probe.expectMsg(Event(s"two$expectedDelimiter"))
      Thread.sleep(1000)
      probe.send(handler, Command(s"ee$delimiter"))
      probe.expectMsg(Event(s"three$expectedDelimiter"))
      probe.send(handler, Command(s"${delimiter}${delimiter}"))
      probe.expectMsg(Event(expectedDelimiter))
      probe.expectMsg(Event(expectedDelimiter))
    }

    def close() {
      probe.send(handler, Tcp.Close)
      probe.expectMsgType[Tcp.Event] match {
        case _: Tcp.ConnectionClosed ⇒ true
      }
      TestUtils.verifyActorTermination(handler)
    }

  }

  class AkkaLineEchoServer(delimiter: String, includeDelimiter: Boolean) extends Actor with ActorLogging {

    import Tcp.Connected

    def receive: Receive = {
      case Connected(remote, _) ⇒
        val init =
          new TcpPipelineHandler.Init(
            new StringByteStringAdapter >>
              new DelimiterFraming(maxSize = 1024, delimiter = ByteString(delimiter), includeDelimiter = includeDelimiter) >>
              new TcpReadWriteAdapter) {
            override def makeContext(actorContext: ActorContext): HasLogging =
              new HasLogging {
                override def getLogger = log
              }
          }
        import init._

        val connection = sender
        val handler = system.actorOf(
          TcpPipelineHandler(init, sender, self), "server" + counter.incrementAndGet())

        connection ! Tcp.Register(handler)

        context become {
          case Event(data) ⇒
            if (includeDelimiter) sender ! Command(data)
            else sender ! Command(data + delimiter)
        }
    }
  }

}
