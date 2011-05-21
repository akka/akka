/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import java.nio.ByteBuffer
import java.nio.channels.{ SelectableChannel, SocketChannel, ServerSocketChannel, SelectionKey }
import java.io.IOException
import java.net.InetSocketAddress

import scala.collection.immutable.Queue

object NIOActorSpec {

  class NIOTestActor(val nioHandler: NIOHandler) extends Actor with NIO {

    val readBuffer = ByteBuffer.allocate(8192)

    var writeBuffers = Map.empty[SocketChannel, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

    override def preStart = {
      val inetAddress = new InetSocketAddress("localhost", 8064)
      val channel = ServerSocketChannel.open()
      channel.configureBlocking(false)
      channel.socket.bind(inetAddress)

      nioHandler.register(channel, SelectionKey.OP_ACCEPT)
    }

    def accept(channel: SelectableChannel): Unit = channel match {
      case serverChannel: ServerSocketChannel ⇒
        val ch = serverChannel.accept()
        ch.configureBlocking(false)
        nioHandler.register(ch, SelectionKey.OP_READ)
    }

    def read(channel: SelectableChannel): Unit = channel match {
      case ch: SocketChannel ⇒
        readBuffer.clear
        try {
          val readLen = ch.read(readBuffer)
          if (readLen == -1) {
            nioHandler.unregister(ch)
            ch.close
          } else {
            val ar = new Array[Byte](readLen)
            readBuffer.flip
            readBuffer.get(ar)
            writeBuffers += (ch -> writeBuffers(ch).enqueue(ByteBuffer.wrap(ar)))
            nioHandler.interestOps(ch, SelectionKey.OP_WRITE)
          }
        } catch {
          case e: IOException ⇒
            nioHandler.unregister(ch)
            ch.close
        }
    }

    def write(channel: SelectableChannel): Unit = {
      def writeChannel(ch: SocketChannel): Unit = {
        val queue = writeBuffers(ch)
        if (queue.nonEmpty) {
          val (buf, bufs) = writeBuffers(ch).dequeue
          ch.write(buf)
          if (buf.remaining == 0) {
            if (bufs.isEmpty) {
              writeBuffers -= ch
              nioHandler.interestOps(ch, SelectionKey.OP_READ)
            } else {
              writeBuffers += (ch -> bufs)
              writeChannel(ch)
            }
          }
        }
      }
      channel match {
        case ch: SocketChannel ⇒
          writeChannel(channel.asInstanceOf[SocketChannel])
      }
    }

    def receive = {
      case msg ⇒ println(msg)
    }
  }
}

class NIOActorSpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import NIOActorSpec._

  "an NIO Actor" must {
    "run" in {
      val nioHandler = new NIOHandler
      val actor = Actor.actorOf(new NIOTestActor(nioHandler)).start
      Thread.sleep(600000)
    }
  }

}
