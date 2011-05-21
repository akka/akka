/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.config.Supervision.Temporary

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object NIOActorSpec {

  class EchoServer(val host: String, val port: Int) extends Actor with NIOSimpleServer {

    def createWorker = new EchoServerWorker(nioHandler)

    def receive = {
      case "Ping" ⇒ self reply_? "Pong"
    }

  }

  class EchoServerWorker(val nioHandler: NIOHandler) extends Actor with NIO {
    val readBuffer = ByteBuffer allocate 8192
    var writeBuffers = Queue.empty[ByteBuffer]

    self.lifeCycle = Temporary

    def receive = {
      case "Ping" ⇒ self reply_? "Pong"
    }

    def receiveIO = {
      case NIO.Read(client: SocketChannel)  ⇒ read(client)
      case NIO.Write(client: SocketChannel) ⇒ write(client)
    }

    @tailrec
    final def read(client: SocketChannel): Unit = {
      readBuffer clear ()
      val readLen = client read readBuffer
      if (readLen == -1) {
        nioHandler unregister client
        client close ()
        self stop ()
      } else {
        val bytes = new Array[Byte](readLen)
        readBuffer flip ()
        readBuffer get bytes
        writeBuffers = writeBuffers enqueue (ByteBuffer wrap bytes)
        nioHandler interestOps (client, NIO.Read, NIO.Write)
        if (readLen == readBuffer.capacity) read(client)
      }
    }

    @tailrec
    final def write(client: SocketChannel): Unit = {
      if (writeBuffers.nonEmpty) {
        val (buf, bufs) = writeBuffers.dequeue
        val writeLen = client write buf
        if (buf.remaining == 0) {
          writeBuffers = bufs
          if (writeBuffers.isEmpty) {
            nioHandler interestOps (client, NIO.Read)
          } else {
            write(client)
          }
        }
      }
    }
  }
}

class NIOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import NIOActorSpec._

  "an NIO Actor" must {
    "run" in {
      val actor = Actor.actorOf(new EchoServer("localhost", 8064)).start
      Thread.sleep(600000)
    }
  }

}
