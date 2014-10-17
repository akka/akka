/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io2

import akka.stream.scaladsl2._
import akka.stream.testkit.AkkaSpec
import akka.util.ByteString

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class TcpFlowSpec extends AkkaSpec with TcpHelper {
  import akka.stream.io2.TcpHelper._
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpPublisher)
      val tcpWriteProbe = new TcpWriteProbe(tcpSubscriber)

      serverConnection.write(testData)
      serverConnection.read(5)
      tcpReadProbe.read(5) should be(testData)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      tcpReadProbe.close()

      //client.read() should be(ByteString.empty)
      server.close()
    }

    "be able to write a sequence of ByteStrings" in {
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      serverConnection.read(256)
      Source(tcpPublisher).connect(Sink.ignore).run()

      Source(testInput).connect(Sink(tcpSubscriber)).run()
      serverConnection.waitRead() should be(expectedOutput)

      server.close()
    }

    "be able to read a sequence of ByteStrings" in {
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      for (in ← testInput) serverConnection.write(in)
      new TcpWriteProbe(tcpSubscriber) // Just register an idle upstream

      val resultFuture = Source(tcpPublisher).fold(ByteString.empty) { case (res, elem) ⇒ res ++ elem }

      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      server.close()
    }

    "half close the connection when output stream is closed" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpPublisher)
      val tcpWriteProbe = new TcpWriteProbe(tcpSubscriber)

      tcpWriteProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()
      server.close()
    }

    "stop reading when the input stream is cancelled" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpPublisher)
      val tcpWriteProbe = new TcpWriteProbe(tcpSubscriber)

      tcpReadProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.subscriberProbe.expectNoMsg(1.second)
      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)
      tcpWriteProbe.close()
      server.close()
    }

    "keep write side open when remote half-closes" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpPublisher)
      val tcpWriteProbe = new TcpWriteProbe(tcpSubscriber)

      // FIXME: here (and above tests) add a chitChat() method ensuring this works even after prior communication
      // there should be a chitchat and non-chitchat version

      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()

      serverConnection.read(5)
      tcpWriteProbe.write(testData)
      serverConnection.waitRead() should be(testData)

      tcpWriteProbe.close()
      // FIXME: expect closed event
      server.close()
    }

    "shut down both streams when connection is completely closed" in {
      // Client gets a PeerClosed event and does not know that the write side is also closed
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpSubscriber, tcpPublisher, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpPublisher)
      val tcpWriteProbe = new TcpWriteProbe(tcpSubscriber)

      serverConnection.abort()
      tcpReadProbe.subscriberProbe.expectError()
      tcpWriteProbe.tcpWriteSubscription.expectCancellation()
    }

    "close the connection when input stream and oputput streams are closed" in {
      pending
    }

  }

  "TCP listen stream" must {

    "be able to implement echo" in {
      val serverAddress = temporaryServerAddress
      val server = echoServer(serverAddress)
      val (tcpSubscriber, tcpPublisher) = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).connect(Sink(tcpSubscriber)).run()
      val resultFuture = Source(tcpPublisher).fold(ByteString.empty) { case (res, elem) ⇒ res ++ elem }

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      server.close()
      server.awaitTermination(3.seconds)
    }

    "work with a chain of echoes" in {
      val serverAddress = temporaryServerAddress
      val server = echoServer(serverAddress)

      val (tcpSubscriber1, tcpPublisher1) = connect(serverAddress)
      val (tcpSubscriber2, tcpPublisher2) = connect(serverAddress)
      val (tcpSubscriber3, tcpPublisher3) = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).connect(Sink(tcpSubscriber1)).run()
      tcpPublisher1.subscribe(tcpSubscriber2)
      tcpPublisher2.subscribe(tcpSubscriber3)
      val resultFuture = Source(tcpPublisher3).fold(ByteString.empty) { case (res, elem) ⇒ res ++ elem }

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      server.close()
      server.awaitTermination(3.seconds)
    }

  }
}
