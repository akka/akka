/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.config.Supervision.Permanent
import akka.util.{ ByteString, ByteRope }
import akka.dispatch.MessageInvocation

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

    def read(len: Int)(implicit actor: Actor with IO): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
      actor.state(this).messages enqueue actor.self.currentMessage
      actor._continuations += (actor.self.currentMessage -> ByteStringLength(cont, len))
      actor.run(this)
    }

    def read()(implicit actor: Actor with IO): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
      actor.state(this).messages enqueue actor.self.currentMessage
      actor._continuations += (actor.self.currentMessage -> ByteStringAny(cont))
      actor.run(this)
    }

    def read(delimiter: ByteString, inclusive: Boolean = false)(implicit actor: Actor with IO): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
      actor.state(this).messages enqueue actor.self.currentMessage
      actor._continuations += (actor.self.currentMessage -> ByteStringDelimited(cont, delimiter, inclusive, 0))
      actor.run(this)
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

    def accept(socketOwner: ActorRef): SocketHandle = {
      val socket = SocketHandle(socketOwner, ioManager)
      ioManager ! Accept(socket, this)
      socket
    }

    def accept()(implicit sender: Some[ActorRef]): SocketHandle = accept(sender.get)
  }

  sealed trait IOMessage
  case class Listen(server: ServerHandle, address: InetSocketAddress) extends IOMessage
  case class NewClient(server: ServerHandle) extends IOMessage
  case class Accept(socket: SocketHandle, server: ServerHandle) extends IOMessage
  case class Connect(socket: SocketHandle, address: InetSocketAddress) extends IOMessage
  case class Connected(socket: SocketHandle) extends IOMessage
  case class Close(handle: Handle) extends IOMessage
  case class Closed(handle: Handle) extends IOMessage
  case class Read(handle: ReadHandle, bytes: ByteString) extends IOMessage
  case class Write(handle: WriteHandle, bytes: ByteString) extends IOMessage

  def listen(ioManager: ActorRef, host: String, port: Int)(implicit sender: Some[ActorRef]): ServerHandle =
    listen(ioManager, new InetSocketAddress(host, port))

  def listen(ioManager: ActorRef, address: InetSocketAddress)(implicit sender: Some[ActorRef]): ServerHandle = {
    val server = ServerHandle(sender.get, ioManager)
    ioManager ! Listen(server, address)
    server
  }

  def connect(ioManager: ActorRef, host: String, port: Int)(implicit sender: Some[ActorRef]): SocketHandle =
    connect(ioManager, new InetSocketAddress(host, port))

  def connect(ioManager: ActorRef, address: InetSocketAddress)(implicit sender: Some[ActorRef]): SocketHandle = {
    val socket = SocketHandle(sender.get, ioManager)
    ioManager ! Connect(socket, address)
    socket
  }

  def loop(block: ⇒ Any @cps[Any]): Unit @cps[Any] = {
    def f(): TailRec[Unit] @cps[Any] = {
      block
      Call(() ⇒ f())
    }
    tailrec(f())
  }

  def loopWhile(test: ⇒ Boolean)(block: ⇒ Any @cps[Any]): Unit @cps[Any] = {
    def f(): TailRec[Unit] @cps[Any] = {
      if (test) {
        block
        Call(() ⇒ f())
      } else Return(())
    }
    tailrec(f())
  }

  private class HandleState(val messages: mutable.Queue[MessageInvocation], var readBytes: ByteRope, var connected: Boolean) {
    def this() = this(mutable.Queue.empty, ByteRope.empty, false)
  }

  private sealed trait IOContinuation[A] { def continuation: (A) ⇒ Any }
  private case class ByteStringLength(continuation: (ByteString) ⇒ Any, length: Int) extends IOContinuation[ByteString]
  private case class ByteStringDelimited(continuation: (ByteString) ⇒ Any, delimter: ByteString, inclusive: Boolean, scanned: Int) extends IOContinuation[ByteString]
  private case class ByteStringAny(continuation: (ByteString) ⇒ Any) extends IOContinuation[ByteString]

  private sealed trait TailRec[A]
  private case class Return[A](result: A) extends TailRec[A]
  private case class Call[A](thunk: () ⇒ TailRec[A] @cps[Any]) extends TailRec[A]
  private def tailrec[A](comp: TailRec[A]): A @cps[Any] = comp match {
    case Call(thunk) ⇒ tailrec(thunk())
    case Return(x)   ⇒ x
  }

}

trait IO {
  this: Actor ⇒
  import IO._

  implicit protected def ioActor: Actor with IO = this

  private val _messages: mutable.Queue[MessageInvocation] = mutable.Queue.empty

  private var _state: Map[Handle, HandleState] = Map.empty

  private var _continuations: Map[MessageInvocation, IOContinuation[_]] = Map.empty

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
      st.readBytes :+= newBytes
      run(handle)
    case msg@Connected(socket) ⇒
      state(socket).connected = true
      if (_receiveIO.isDefinedAt(msg)) reset { _receiveIO(msg) }
    case msg@Closed(handle) ⇒
      _state -= handle // TODO: clean up better
      if (_receiveIO.isDefinedAt(msg)) reset { _receiveIO(msg) }
    case msg if _continuations.nonEmpty ⇒
      _messages enqueue self.currentMessage
    case msg if _receiveIO.isDefinedAt(msg) ⇒
      reset { _receiveIO(msg) }
      ()
  }

  def receiveIO: PartialFunction[Any, Any @cps[Any]]

  private lazy val _receiveIO = receiveIO

  @tailrec
  private def run(handle: Handle): Unit = {
    val st = state(handle)
    if (st.messages.nonEmpty) {
      val msg = st.messages.head
      self.currentMessage = msg
      _continuations.get(msg) match {
        case Some(ByteStringLength(continuation, waitingFor)) ⇒
          if (st.readBytes.length >= waitingFor) {
            st.messages.dequeue
            val bytes = st.readBytes.take(waitingFor).toByteString
            st.readBytes = st.readBytes.drop(waitingFor)
            _continuations -= msg
            continuation(bytes)
            run(handle)
          }
        case Some(ByteStringDelimited(continuation, delimiter, inclusive, scanned)) ⇒
          val idx = st.readBytes.indexOfSlice(delimiter, scanned)
          if (idx >= 0) {
            st.messages.dequeue
            val index = if (inclusive) idx + delimiter.length else idx
            val bytes = st.readBytes.take(index).toByteString
            st.readBytes = st.readBytes.drop(idx + delimiter.length)
            _continuations -= msg
            continuation(bytes)
            run(handle)
          } else {
            _continuations += (msg -> ByteStringDelimited(continuation, delimiter, inclusive, math.min(idx - delimiter.length, 0)))
          }
        case Some(ByteStringAny(continuation)) ⇒
          if (st.readBytes.length > 0) {
            st.messages.dequeue
            val bytes = st.readBytes.toByteString
            st.readBytes = ByteRope.empty
            _continuations -= msg
            continuation(bytes)
            run(handle)
          }
        case _ ⇒ sys error "Unhandled or missing IOContinuation"
      }
    } else {
      while (_continuations.isEmpty && _messages.nonEmpty) {
        self invoke _messages.dequeue
      }
    }
  }
}

class IOManager(bufferSize: Int = 8192) extends Actor {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  var worker: IOWorker = _

  override def preStart: Unit = {
    worker = new IOWorker(self, bufferSize)
    worker.start
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

  override def postStop: Unit = {
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

private[akka] class IOWorker(ioManager: ActorRef, val bufferSize: Int) {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  implicit val optionIOManager: Some[ActorRef] = Some(ioManager)

  def apply(request: Request): Unit =
    addRequest(request)

  def start(): Unit =
    thread.start

  // private

  private val selector: Selector = Selector open ()

  private val _requests = new AtomicReference(List.empty[Request])

  private var accepted = Map.empty[IO.ServerHandle, Queue[SelectableChannel]].withDefaultValue(Queue.empty)

  private var channels = Map.empty[IO.Handle, SelectableChannel]

  private var writes = Map.empty[IO.WriteHandle, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

  private val buffer = ByteBuffer.allocate(bufferSize)

  private val thread = new Thread() {
    override def run(): Unit = {
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
            cleanup(handle)
          case Shutdown ⇒
            channels.values foreach (_.close)
            selector.close
        }
      }
    }
  }

  private def process(key: SelectionKey): Unit = {
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
        case channel: WriteChannel ⇒ write(handle.asWritable, channel)
      }
    } catch {
      case e: CancelledKeyException ⇒ cleanup(handle)
    }
  }

  private def cleanup(handle: IO.Handle): Unit = {
    handle match {
      case server: IO.ServerHandle  ⇒ accepted -= server
      case writable: IO.WriteHandle ⇒ writes -= writable
    }
    channels.get(handle) match {
      case Some(channel) ⇒
        channel.close
        channels -= handle
        handle.owner ! IO.Closed(handle)
      case None ⇒
    }
  }

  private def setOps(handle: IO.Handle, ops: Int): Unit =
    channels(handle) keyFor selector interestOps ops

  private def addOps(handle: IO.Handle, ops: Int): Unit = {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur | ops)
  }

  private def removeOps(handle: IO.Handle, ops: Int): Unit = {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur - (cur & ops))
  }

  private def connect(socket: IO.SocketHandle, channel: SocketChannel): Unit = {
    channel.finishConnect
    removeOps(socket, OP_CONNECT)
    socket.owner ! IO.Connected(socket)
  }

  @tailrec
  private def accept(server: IO.ServerHandle, channel: ServerSocketChannel): Unit = {
    val socket = channel.accept
    if (socket ne null) {
      socket configureBlocking false
      accepted += (server -> (accepted(server) enqueue socket))
      server.owner ! IO.NewClient(server)
      accept(server, channel)
    }
  }

  @tailrec
  private def read(handle: IO.ReadHandle, channel: ReadChannel): Unit = {
    buffer.clear
    val readLen = channel read buffer
    if (readLen == -1) {
      cleanup(handle)
    } else if (readLen > 0) {
      buffer.flip
      handle.owner ! IO.Read(handle, ByteString(buffer))
      if (readLen == buffer.capacity) read(handle, channel)
    }
  }

  @tailrec
  private def write(handle: IO.WriteHandle, channel: WriteChannel): Unit = {
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
  private def addRequest(req: Request): Unit = {
    val requests = _requests.get
    if (_requests compareAndSet (requests, req :: requests))
      selector wakeup ()
    else
      addRequest(req)
  }
}
