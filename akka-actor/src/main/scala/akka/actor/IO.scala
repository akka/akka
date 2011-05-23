/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.config.Supervision.Permanent
import akka.util.ByteString

import java.nio.ByteBuffer
import java.nio.channels.{ SelectableChannel, ReadableByteChannel, WritableByteChannel, ServerSocketChannel, Selector, SelectionKey, CancelledKeyException }
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections.synchronizedSet
import java.util.HashSet

import scala.collection.immutable.Queue
import scala.annotation.tailrec

object IO {

  case class CreateServer(owner: ActorRef, host: String, port: Int)
  case object NewConnection
  case class AcceptConnection(server: ActorRef, owner: ActorRef)
  case object Close
  case object Closed

  case class Read(bytes: ByteString)
  case class Write(owner: ActorRef, bytes: ByteString)

}

class IOManager extends Actor {

  var worker: IOWorker = _
  var workerThread: Thread = _

  override def preStart: Unit = {
    worker = new IOWorker(self)
    workerThread = new Thread(worker)
    workerThread.start
  }

  def receive = {
    case IO.CreateServer(owner, host, port) ⇒ worker.createServer(owner, host, port)
    case IO.AcceptConnection(server, owner) ⇒ worker.acceptConnection(server, owner)
    case IO.Write(owner, data)              ⇒ worker.write(owner, data)
  }

}

private[akka] object IOWorker {
  sealed trait ChangeRequest
  case class Register(owner: ActorRef, channel: SelectableChannel, ops: Int) extends ChangeRequest
  case class Accepted(server: ActorRef, owner: ActorRef) extends ChangeRequest
  case class QueueWrite(owner: ActorRef, data: ByteBuffer) extends ChangeRequest
  case class InterestOps(channel: SelectableChannel, interestOps: Int) extends ChangeRequest
}

private[akka] class IOWorker(ioManager: ActorRef) extends Runnable {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  implicit val optionIOManager: Some[ActorRef] = Some(ioManager)

  val bufferSize = 8192 // make configurable

  def createServer(owner: ActorRef, host: String, port: Int): Unit = {
    val inetAddress = new InetSocketAddress(host: String, port: Int)
    val server = ServerSocketChannel open ()
    server configureBlocking false
    server.socket bind inetAddress
    addChangeRequest(Register(owner, server, OP_ACCEPT))
  }

  def acceptConnection(server: ActorRef, owner: ActorRef): Unit =
    addChangeRequest(Accepted(server, owner))

  def write(owner: ActorRef, data: ByteString): Unit =
    addChangeRequest(QueueWrite(owner, data.asByteBuffer))

  // private

  private val selector: Selector = Selector open ()

  private val _changeRequests = new AtomicReference(List.empty[ChangeRequest])

  private var acceptedChannels = Map.empty[ActorRef, Queue[SelectableChannel]].withDefaultValue(Queue.empty)

  private var channels = Map.empty[ActorRef, SelectableChannel]

  private var writeQueues = Map.empty[ActorRef, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

  private val buffer = ByteBuffer.allocate(bufferSize)

  def run(): Unit = {
    while (selector.isOpen) {
      val keys = selector.selectedKeys.iterator
      while (keys.hasNext) {
        val key = keys next ()
        keys remove ()
        if (key.isValid) { process(key) }
      }
      _changeRequests.getAndSet(Nil).reverse foreach {
        case Register(owner, channel, ops) ⇒ channel register (selector, ops, owner)
        case Accepted(server, owner) ⇒
          val (channel, rest) = acceptedChannels(server).dequeue
          if (rest.isEmpty) acceptedChannels -= server
          else acceptedChannels += (server -> rest)
          channels += (owner -> channel)
          channel register (selector, OP_READ, owner)
        case QueueWrite(owner, data) ⇒
          if (channels contains owner) {
            val queue = writeQueues(owner)
            if (queue.isEmpty) channels(owner) keyFor selector interestOps OP_WRITE
            writeQueues += (owner -> queue.enqueue(data))
          }
        case InterestOps(channel, ops) ⇒ channel keyFor selector interestOps ops
      }
      selector select ()
    }
  }

  private def process(key: SelectionKey): Unit = try {
    val owner = key.attachment.asInstanceOf[ActorRef]
    //if (key.isConnectable) connected(key)
    if (key.isAcceptable) key.channel match {
      case server: ServerSocketChannel ⇒ accept(owner, server)
    }
    if (key.isReadable) key.channel match {
      case channel: ReadChannel ⇒ read(owner, channel)
    }
    if (key.isWritable) key.channel match {
      case channel: WriteChannel ⇒ write(owner, channel)
    }
  } catch {
    case e: CancelledKeyException ⇒ println("Got " + e)
  }

  @tailrec
  final def accept(owner: ActorRef, server: ServerSocketChannel): Unit = {
    val client = server.accept
    if (client ne null) {
      client configureBlocking false
      acceptedChannels += (owner -> (acceptedChannels(owner) enqueue client))
      owner ! IO.NewConnection
      accept(owner, server)
    }
  }

  @tailrec
  final def read(owner: ActorRef, channel: ReadChannel): Unit = {
    buffer.clear
    val readLen = channel read buffer
    if (readLen == -1) {
      channel.close
      channels -= owner
      owner ! IO.Closed
    } else if (readLen > 0) {
      buffer.flip
      owner ! IO.Read(ByteString(buffer))
      if (readLen == buffer.capacity) read(owner, channel)
    }
  }

  @tailrec
  final def write(owner: ActorRef, channel: WriteChannel): Unit = {
    val queue = writeQueues(owner)
    if (queue.nonEmpty) {
      val (buf, bufs) = queue.dequeue
      val writeLen = channel write buf
      if (buf.remaining == 0) {
        if (bufs.isEmpty) {
          writeQueues -= owner
          addChangeRequest(InterestOps(channel, OP_READ))
        } else {
          writeQueues += (owner -> bufs)
          write(owner, channel)
        }
      }
    }
  }

  @tailrec
  private def addChangeRequest(req: ChangeRequest): Unit = {
    val changeRequests = _changeRequests.get
    if (_changeRequests compareAndSet (changeRequests, req :: changeRequests))
      selector wakeup ()
    else
      addChangeRequest(req)
  }
}
