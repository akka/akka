/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.util.ByteString
import akka.dispatch.Envelope
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer
import java.nio.channels.{
  SelectableChannel,
  ReadableByteChannel,
  WritableByteChannel,
  SocketChannel,
  ServerSocketChannel,
  Selector,
  SelectionKey,
  CancelledKeyException
}
import scala.collection.mutable
import scala.collection.immutable.Queue
import scala.annotation.tailrec
import scala.util.continuations._
import com.eaio.uuid.UUID

object IO {

  sealed trait Handle {
    this: Product ⇒
    def owner: ActorRef
    def ioManager: ActorRef
    def uuid: UUID
    override lazy val hashCode = scala.runtime.ScalaRunTime._hashCode(this)

    def asReadable: ReadHandle = sys error "Not readable"
    def asWritable: WriteHandle = sys error "Not writable"
    def asSocket: SocketHandle = sys error "Not a socket"
    def asServer: ServerHandle = sys error "Not a server"

    def close(): Unit = ioManager ! Close(this)
  }

  sealed trait ReadHandle extends Handle with Product {
    override def asReadable = this

    def read(len: Int)(implicit actor: Actor with IO): ByteString @cps[IOSuspendable[Any]] = shift { cont: (ByteString ⇒ IOSuspendable[Any]) ⇒
      ByteStringLength(cont, this, actor.context.currentMessage, len)
    }

    def read()(implicit actor: Actor with IO): ByteString @cps[IOSuspendable[Any]] = shift { cont: (ByteString ⇒ IOSuspendable[Any]) ⇒
      ByteStringAny(cont, this, actor.context.currentMessage)
    }

    def read(delimiter: ByteString, inclusive: Boolean = false)(implicit actor: Actor with IO): ByteString @cps[IOSuspendable[Any]] = shift { cont: (ByteString ⇒ IOSuspendable[Any]) ⇒
      ByteStringDelimited(cont, this, actor.context.currentMessage, delimiter, inclusive, 0)
    }
  }

  sealed trait WriteHandle extends Handle with Product {
    override def asWritable = this

    def write(bytes: ByteString): Unit = ioManager ! Write(this, bytes)
  }

  case class SocketHandle(owner: ActorRef, ioManager: ActorRef, uuid: UUID = new UUID()) extends ReadHandle with WriteHandle {
    override def asSocket = this
  }

  case class ServerHandle(owner: ActorRef, ioManager: ActorRef, uuid: UUID = new UUID()) extends Handle {
    override def asServer = this

    def accept()(implicit socketOwner: ActorRef): SocketHandle = {
      val socket = SocketHandle(socketOwner, ioManager)
      ioManager ! Accept(socket, this)
      socket
    }
  }

  sealed trait IOMessage
  case class Listen(server: ServerHandle, address: InetSocketAddress) extends IOMessage
  case class NewClient(server: ServerHandle) extends IOMessage
  case class Accept(socket: SocketHandle, server: ServerHandle) extends IOMessage
  case class Connect(socket: SocketHandle, address: InetSocketAddress) extends IOMessage
  case class Connected(socket: SocketHandle) extends IOMessage
  case class Close(handle: Handle) extends IOMessage
  case class Closed(handle: Handle, cause: Option[Exception]) extends IOMessage
  case class Read(handle: ReadHandle, bytes: ByteString) extends IOMessage
  case class Write(handle: WriteHandle, bytes: ByteString) extends IOMessage

  def listen(ioManager: ActorRef, address: InetSocketAddress)(implicit owner: ActorRef): ServerHandle = {
    val server = ServerHandle(owner, ioManager)
    ioManager ! Listen(server, address)
    server
  }

  def listen(ioManager: ActorRef, host: String, port: Int)(implicit owner: ActorRef): ServerHandle =
    listen(ioManager, new InetSocketAddress(host, port))

  def connect(ioManager: ActorRef, address: InetSocketAddress)(implicit owner: ActorRef): SocketHandle = {
    val socket = SocketHandle(owner, ioManager)
    ioManager ! Connect(socket, address)
    socket
  }

  def connect(ioManager: ActorRef, host: String, port: Int)(implicit sender: ActorRef): SocketHandle =
    connect(ioManager, new InetSocketAddress(host, port))

  private class HandleState(var readBytes: ByteString, var connected: Boolean) {
    def this() = this(ByteString.empty, false)
  }

  sealed trait IOSuspendable[+A]
  sealed trait CurrentMessage { def message: Envelope }
  private case class ByteStringLength(continuation: (ByteString) ⇒ IOSuspendable[Any], handle: Handle, message: Envelope, length: Int) extends IOSuspendable[ByteString] with CurrentMessage
  private case class ByteStringDelimited(continuation: (ByteString) ⇒ IOSuspendable[Any], handle: Handle, message: Envelope, delimter: ByteString, inclusive: Boolean, scanned: Int) extends IOSuspendable[ByteString] with CurrentMessage
  private case class ByteStringAny(continuation: (ByteString) ⇒ IOSuspendable[Any], handle: Handle, message: Envelope) extends IOSuspendable[ByteString] with CurrentMessage
  private case class Retry(message: Envelope) extends IOSuspendable[Nothing]
  private case object Idle extends IOSuspendable[Nothing]

}

trait IO {
  this: Actor ⇒
  import IO._

  type ReceiveIO = PartialFunction[Any, Any @cps[IOSuspendable[Any]]]

  implicit protected def ioActor: Actor with IO = this

  private val _messages: mutable.Queue[Envelope] = mutable.Queue.empty

  private var _state: Map[Handle, HandleState] = Map.empty

  private var _next: IOSuspendable[Any] = Idle

  private def state(handle: Handle): HandleState = _state.get(handle) match {
    case Some(s) ⇒ s
    case _ ⇒
      val s = new HandleState()
      _state += (handle -> s)
      s
  }

  final def receive: Receive = {
    case Read(handle, newBytes) ⇒
      val st = state(handle)
      st.readBytes ++= newBytes
      run()
    case Connected(socket) ⇒
      state(socket).connected = true
      run()
    case msg @ Closed(handle, _) ⇒
      _state -= handle // TODO: clean up better
      if (_receiveIO.isDefinedAt(msg)) {
        _next = reset { _receiveIO(msg); Idle }
      }
      run()
    case msg if _next ne Idle ⇒
      _messages enqueue context.currentMessage
    case msg if _receiveIO.isDefinedAt(msg) ⇒
      _next = reset { _receiveIO(msg); Idle }
      run()
  }

  def receiveIO: ReceiveIO

  def retry(): Any @cps[IOSuspendable[Any]] =
    shift { _: (Any ⇒ IOSuspendable[Any]) ⇒
      _next match {
        case n: CurrentMessage ⇒ Retry(n.message)
        case _                 ⇒ Idle
      }
    }

  private lazy val _receiveIO = receiveIO

  // only reinvoke messages from the original message to avoid stack overflow
  private var reinvoked = false
  private def reinvoke() {
    if (!reinvoked && (_next eq Idle) && _messages.nonEmpty) {
      try {
        reinvoked = true
        while ((_next eq Idle) && _messages.nonEmpty) self.asInstanceOf[LocalActorRef].underlying invoke _messages.dequeue
      } finally {
        reinvoked = false
      }
    }
  }

  @tailrec
  private def run() {
    _next match {
      case ByteStringLength(continuation, handle, message, waitingFor) ⇒
        context.currentMessage = message
        val st = state(handle)
        if (st.readBytes.length >= waitingFor) {
          val bytes = st.readBytes.take(waitingFor) //.compact
          st.readBytes = st.readBytes.drop(waitingFor)
          _next = continuation(bytes)
          run()
        }
      case bsd @ ByteStringDelimited(continuation, handle, message, delimiter, inclusive, scanned) ⇒
        context.currentMessage = message
        val st = state(handle)
        val idx = st.readBytes.indexOfSlice(delimiter, scanned)
        if (idx >= 0) {
          val index = if (inclusive) idx + delimiter.length else idx
          val bytes = st.readBytes.take(index) //.compact
          st.readBytes = st.readBytes.drop(idx + delimiter.length)
          _next = continuation(bytes)
          run()
        } else {
          _next = bsd.copy(scanned = math.min(idx - delimiter.length, 0))
        }
      case ByteStringAny(continuation, handle, message) ⇒
        context.currentMessage = message
        val st = state(handle)
        if (st.readBytes.length > 0) {
          val bytes = st.readBytes //.compact
          st.readBytes = ByteString.empty
          _next = continuation(bytes)
          run()
        }
      case Retry(message) ⇒
        message +=: _messages
        _next = Idle
        run()
      case Idle ⇒ reinvoke()
    }
  }
}

class IOManager(bufferSize: Int = 8192) extends Actor {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  var worker: IOWorker = _

  override def preStart {
    worker = new IOWorker(context.system, self, bufferSize)
    worker.start()
  }

  def receive = {
    case IO.Listen(server, address) ⇒
      val channel = ServerSocketChannel open ()
      channel configureBlocking false
      channel.socket bind address
      worker(Register(server, channel, OP_ACCEPT))

    case IO.Connect(socket, address) ⇒
      val channel = SocketChannel open ()
      channel configureBlocking false
      channel connect address
      worker(Register(socket, channel, OP_CONNECT | OP_READ))

    case IO.Accept(socket, server) ⇒ worker(Accepted(socket, server))
    case IO.Write(handle, data)    ⇒ worker(Write(handle, data.asByteBuffer))
    case IO.Close(handle)          ⇒ worker(Close(handle))
  }

  override def postStop {
    worker(Shutdown)
  }

}

private[akka] object IOWorker {
  sealed trait Request
  case class Register(handle: IO.Handle, channel: SelectableChannel, ops: Int) extends Request
  case class Accepted(socket: IO.SocketHandle, server: IO.ServerHandle) extends Request
  case class Write(handle: IO.WriteHandle, data: ByteBuffer) extends Request
  case class Close(handle: IO.Handle) extends Request
  case object Shutdown extends Request
}

private[akka] class IOWorker(system: ActorSystem, ioManager: ActorRef, val bufferSize: Int) {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  implicit val optionIOManager: Some[ActorRef] = Some(ioManager)

  def apply(request: Request): Unit =
    addRequest(request)

  def start(): Unit =
    thread.start()

  // private

  private val selector: Selector = Selector open ()

  private val _requests = new AtomicReference(List.empty[Request])

  private var accepted = Map.empty[IO.ServerHandle, Queue[SelectableChannel]].withDefaultValue(Queue.empty)

  private var channels = Map.empty[IO.Handle, SelectableChannel]

  private var writes = Map.empty[IO.WriteHandle, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

  private val buffer = ByteBuffer.allocate(bufferSize)

  private val thread = new Thread("io-worker") {
    override def run() {
      while (selector.isOpen) {
        selector select ()
        val keys = selector.selectedKeys.iterator
        while (keys.hasNext) {
          val key = keys next ()
          keys remove ()
          if (key.isValid) { process(key) }
        }
        _requests.getAndSet(Nil).reverse foreach {
          case Register(handle, channel, ops) ⇒
            channels += (handle -> channel)
            channel register (selector, ops, handle)
          case Accepted(socket, server) ⇒
            val (channel, rest) = accepted(server).dequeue
            if (rest.isEmpty) accepted -= server
            else accepted += (server -> rest)
            channels += (socket -> channel)
            channel register (selector, OP_READ, socket)
          case Write(handle, data) ⇒
            if (channels contains handle) {
              val queue = writes(handle)
              if (queue.isEmpty) addOps(handle, OP_WRITE)
              writes += (handle -> queue.enqueue(data))
            }
          case Close(handle) ⇒
            cleanup(handle, None)
          case Shutdown ⇒
            channels.values foreach (_.close)
            selector.close
        }
      }
    }
  }

  private def process(key: SelectionKey) {
    val handle = key.attachment.asInstanceOf[IO.Handle]
    try {
      if (key.isConnectable) key.channel match {
        case channel: SocketChannel ⇒ connect(handle.asSocket, channel)
      }
      if (key.isAcceptable) key.channel match {
        case channel: ServerSocketChannel ⇒ accept(handle.asServer, channel)
      }
      if (key.isReadable) key.channel match {
        case channel: ReadChannel ⇒ read(handle.asReadable, channel)
      }
      if (key.isWritable) key.channel match {
        case channel: WriteChannel ⇒
          try {
            write(handle.asWritable, channel)
          } catch {
            case e: IOException ⇒
            // ignore, let it fail on read to ensure nothing left in read buffer.
          }
      }
    } catch {
      case e: CancelledKeyException        ⇒ cleanup(handle, Some(e))
      case e: IOException                  ⇒ cleanup(handle, Some(e))
      case e: ActorInitializationException ⇒ cleanup(handle, Some(e))
    }
  }

  private def cleanup(handle: IO.Handle, cause: Option[Exception]) {
    handle match {
      case server: IO.ServerHandle  ⇒ accepted -= server
      case writable: IO.WriteHandle ⇒ writes -= writable
    }
    channels.get(handle) match {
      case Some(channel) ⇒
        channel.close
        channels -= handle
        // TODO: what if handle.owner is no longer running?
        handle.owner ! IO.Closed(handle, cause)
      case None ⇒
    }
  }

  private def setOps(handle: IO.Handle, ops: Int): Unit =
    channels(handle) keyFor selector interestOps ops

  private def addOps(handle: IO.Handle, ops: Int) {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur | ops)
  }

  private def removeOps(handle: IO.Handle, ops: Int) {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur - (cur & ops))
  }

  private def connect(socket: IO.SocketHandle, channel: SocketChannel) {
    if (channel.finishConnect) {
      removeOps(socket, OP_CONNECT)
      socket.owner ! IO.Connected(socket)
    } else {
      cleanup(socket, None) // TODO: Add a cause
    }
  }

  @tailrec
  private def accept(server: IO.ServerHandle, channel: ServerSocketChannel) {
    val socket = channel.accept
    if (socket ne null) {
      socket configureBlocking false
      accepted += (server -> (accepted(server) enqueue socket))
      server.owner ! IO.NewClient(server)
      accept(server, channel)
    }
  }

  @tailrec
  private def read(handle: IO.ReadHandle, channel: ReadChannel) {
    buffer.clear
    val readLen = channel read buffer
    if (readLen == -1) {
      cleanup(handle, None) // TODO: Add a cause
    } else if (readLen > 0) {
      buffer.flip
      handle.owner ! IO.Read(handle, ByteString(buffer))
      if (readLen == buffer.capacity) read(handle, channel)
    }
  }

  @tailrec
  private def write(handle: IO.WriteHandle, channel: WriteChannel) {
    val queue = writes(handle)
    if (queue.nonEmpty) {
      val (buf, bufs) = queue.dequeue
      val writeLen = channel write buf
      if (buf.remaining == 0) {
        if (bufs.isEmpty) {
          writes -= handle
          removeOps(handle, OP_WRITE)
        } else {
          writes += (handle -> bufs)
          write(handle, channel)
        }
      }
    }
  }

  @tailrec
  private def addRequest(req: Request) {
    val requests = _requests.get
    if (_requests compareAndSet (requests, req :: requests))
      selector wakeup ()
    else
      addRequest(req)
  }
}
