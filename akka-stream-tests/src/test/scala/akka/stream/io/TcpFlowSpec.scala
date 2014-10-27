/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.util.ByteString
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Sink

class TcpFlowSpec extends AkkaSpec with TcpHelper {
  import akka.stream.io.TcpHelper._
  var demand = 0L

  "Outgoing TCP stream" must {

    "work in the happy case" in {
      val testData = ByteString(1, 2, 3, 4, 5)

      val server = new Server()

      val (tcpProcessor, serverConnection) = connect(server)

      val tcpReadProbe = new TcpReadProbe(tcpProcessor)
      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)

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
      val (tcpProcessor, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      serverConnection.read(256)
      Source(tcpProcessor).connect(Sink.ignore).run()

      Source(testInput).runWith(Sink.publisher).subscribe(tcpProcessor)
      serverConnection.waitRead() should be(expectedOutput)

    }

    "be able to read a sequence of ByteStrings" in {
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      for (in ← testInput) serverConnection.write(in)
      new TcpWriteProbe(tcpProcessor) // Just register an idle upstream

      val resultFuture = Source(tcpProcessor).fold(ByteString.empty)((acc, in) ⇒ acc ++ in)
      serverConnection.confirmedClose()
      Await.result(resultFuture, 3.seconds) should be(expectedOutput)

    }

    "half close the connection when output stream is closed" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      tcpWriteProbe.close()
      // FIXME: expect PeerClosed on server
      serverConnection.write(testData)
      tcpReadProbe.read(5) should be(testData)
      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()
    }

    "stop reading when the input stream is cancelled" in {
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

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
      val testData = ByteString(1, 2, 3, 4, 5)
      val server = new Server()
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

      // FIXME: here (and above tests) add a chitChat() method ensuring this works even after prior communication
      // there should be a chitchat and non-chitchat version

      serverConnection.confirmedClose()
      tcpReadProbe.subscriberProbe.expectComplete()

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
      val (tcpProcessor, serverConnection) = connect(server)

      val tcpWriteProbe = new TcpWriteProbe(tcpProcessor)
      val tcpReadProbe = new TcpReadProbe(tcpProcessor)

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
      val conn = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).connect(Sink(conn.outputStream)).run()
      val resultFuture = Source(conn.inputStream).fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      server.close()
      server.awaitTermination(3.seconds)
    }

    "work with a chain of echoes" in {

      val serverAddress = temporaryServerAddress
      val server = echoServer(serverAddress)

      val conn1 = connect(serverAddress)
      val conn2 = connect(serverAddress)
      val conn3 = connect(serverAddress)

      val testInput = Iterator.range(0, 256).map(ByteString(_))
      val expectedOutput = ByteString(Array.tabulate(256)(_.asInstanceOf[Byte]))

      Source(testInput).connect(Sink(conn1.outputStream)).run()
      conn1.inputStream.subscribe(conn2.outputStream)
      conn2.inputStream.subscribe(conn3.outputStream)
      val resultFuture = Source(conn3.inputStream).fold(ByteString.empty)((acc, in) ⇒ acc ++ in)

      Await.result(resultFuture, 3.seconds) should be(expectedOutput)
      server.close()
      server.awaitTermination(3.seconds)
    }

  }

}
