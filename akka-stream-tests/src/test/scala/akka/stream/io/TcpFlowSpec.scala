/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.io.StreamTcp.{ TcpServerBinding, IncomingTcpConnection }
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.util.ByteString
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import akka.stream.scaladsl._

class TcpFlowSpec extends AkkaSpec with TcpHelper {
  import akka.stream.io.TcpHelper._
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val tcpReadProbe = new TcpReadProbe()
      val tcpWriteProbe = new TcpWriteProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp2.connect(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      serverConnection.write(testData)
      serverConnection.read(5)
      tcpReadProbe.read(5) should be(testData)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      tcpReadProbe.close()

      server.close()
    }

    "be able to write a sequence of ByteStrings" in {
      val server = new Server()
      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).via(StreamTcp2.connect(server.address)).to(Sink.ignore).run()

      val serverConnection = server.waitAccept()
      serverConnection.read(256)
      serverConnection.waitRead() should be(expectedOutput)

    }

    "be able to read a sequence of ByteStrings" in {
      val server = new Server()
      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      val idle = new TcpWriteProbe() // Just register an idle upstream
      val resultFuture =
        Source(idle.publisherProbe)
          .via(StreamTcp2.connect(server.address))
          .fold(ByteString.empty)((acc, in) ⇒ acc ++ in)
      val serverConnection = server.waitAccept()

      for (in ← testInput) serverConnection.write(in)

      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "half close the connection when output stream is closed" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp2.connect(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      tcpWriteProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()
    }

    "stop reading when the input stream is cancelled" in {
      val server = new Server()
      val testData = ByteString(1, 2, 3, 4, 5)

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()
      Source(tcpWriteProbe.publisherProbe).via(StreamTcp2.connect(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      tcpReadProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.subscriberProbe.expectNoMsg(1.second)
      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)
      tcpWriteProbe.close()
    }

    "keep write side open when remote half-closes" in {
      val server = new Server()
      val testData = ByteString(1, 2, 3, 4, 5)

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp2.connect(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      // FIXME: here (and above tests) add a chitChat() method ensuring this works even after prior communication
      // there should be a chitchat and non-chitchat version

      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectCompletedOrSubscriptionFollowedByComplete()

      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      // FIXME: expect closed event
    }

    "shut down both streams when connection is completely closed" in {
      // Client gets a PeerClosed event and does not know that the write side is also closed
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()

      val tcpWriteProbe = new TcpWriteProbe()
      val tcpReadProbe = new TcpReadProbe()

      Source(tcpWriteProbe.publisherProbe).via(StreamTcp2.connect(server.address)).to(Sink(tcpReadProbe.subscriberProbe)).run()
      val serverConnection = server.waitAccept()

      serverConnection.abort()
      tcpReadProbe.subscriberProbe.expectErrorOrSubscriptionFollowedByError()
      tcpWriteProbe.tcpWriteSubscription.expectCancellation()
    }

    "close the connection when input stream and oputput streams are closed" in {
      pending
    }

  }

  "TCP listen stream" must {

    // Reusing handler
    val echoHandler = ForeachSink[IncomingTcpConnection] { incoming ⇒
      Source(incoming.inputStream).to(Sink(incoming.outputStream)).run()
    }

    "be able to implement echo" in {
      import system.dispatcher

      val serverAddress = temporaryServerAddress
      val binding = StreamTcp2.bind(serverAddress)
      val echoServer = binding.to(echoHandler).run()

      val echoServerFinish = echoServer.get(echoHandler)
      val echoServerBinding = echoServer.get(binding)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))
      val resultFuture =
        Source(testInput).via(StreamTcp2.connect(serverAddress)).fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      echoServerBinding.foreach(_.close)
      Await.result(echoServerFinish, 3.seconds)
    }

    "work with a chain of echoes" in {
      import system.dispatcher

      val serverAddress = temporaryServerAddress
      val binding = StreamTcp2.bind(serverAddress)
      val echoServer = binding.to(echoHandler).run()

      val echoServerFinish = echoServer.get(echoHandler)
      val echoServerBinding = echoServer.get(binding)

      val echoConnection = StreamTcp2.connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      val resultFuture =
        Source(testInput)
          .via(echoConnection) // The echoConnection is reusable
          .via(echoConnection)
          .via(echoConnection)
          .via(echoConnection)
          .fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      echoServerBinding.foreach(_.close)
      Await.result(echoServerFinish, 3.seconds)
    }

  }

}
