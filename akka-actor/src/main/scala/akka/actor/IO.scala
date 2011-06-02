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

  case class Handle(owner: ActorRef, ioManager: ActorRef, uuid: UUID = new UUID()) {
    override lazy val hashCode = scala.runtime.ScalaRunTime._hashCode(this)
  }

  trait IOMessage { def handle: Handle }
  case class Listen(handle: Handle, address: InetSocketAddress) extends IOMessage
  case class NewConnection(handle: Handle) extends IOMessage
  case class Accept(handle: Handle, source: Handle) extends IOMessage
  case class Connect(handle: Handle, address: InetSocketAddress) extends IOMessage
  case class Connected(handle: Handle) extends IOMessage
  case class Close(handle: Handle) extends IOMessage
  case class Closed(handle: Handle) extends IOMessage
  case class Read(handle: Handle, bytes: ByteString) extends IOMessage
  case class Write(handle: Handle, bytes: ByteString) extends IOMessage
  case class WakeUp(handle: Handle) extends IOMessage

}

trait IO {
  this: Actor ⇒

  def listen(ioManager: ActorRef, host: String, port: Int): IO.Handle =
    listen(ioManager, new InetSocketAddress(host, port))

  def listen(ioManager: ActorRef, address: InetSocketAddress): IO.Handle = {
    val handle = IO.Handle(self, ioManager)
    ioManager ! IO.Listen(handle, address)
    handle
  }

  def connect(ioManager: ActorRef, host: String, port: Int): IO.Handle =
    connect(ioManager, new InetSocketAddress(host, port))

  def connect(ioManager: ActorRef, address: InetSocketAddress): IO.Handle = {
    val handle = IO.Handle(self, ioManager)
    ioManager ! IO.Connect(handle, address)
    handle
  }

  def accept(source: IO.Handle, owner: ActorRef): IO.Handle = {
    val ioManager = source.ioManager
    val handle = IO.Handle(owner, ioManager)
    ioManager ! IO.Accept(handle, source)
    handle
  }

  def accept(source: IO.Handle): IO.Handle = accept(source, self)

  def write(handle: IO.Handle, bytes: ByteString): Unit =
    handle.ioManager ! IO.Write(handle, bytes)

  def close(handle: IO.Handle): Unit =
    handle.ioManager ! IO.Close(handle)

}

object IOActor {
  class HandleState(val messages: mutable.Queue[MessageInvocation], var readBytes: ByteRope, var connected: Boolean) {
    def this() = this(mutable.Queue.empty, ByteRope.empty, false)
  }

  sealed trait IOContinuation[A] { def continuation: (A) ⇒ Any }
  case class ByteStringLength(continuation: (ByteString) ⇒ Any, length: Int) extends IOContinuation[ByteString]
  case class ByteStringDelimited(continuation: (ByteString) ⇒ Any, delimter: ByteString, inclusive: Boolean, scanned: Int) extends IOContinuation[ByteString]
  case class ByteStringAny(continuation: (ByteString) ⇒ Any) extends IOContinuation[ByteString]

  sealed trait TailRec[A]
  case class Return[A](result: A) extends TailRec[A]
  case class Call[A](thunk: () ⇒ TailRec[A] @cps[Any]) extends TailRec[A]
  def tailrec[A](comp: TailRec[A]): A @cps[Any] = comp match {
    case Call(thunk) ⇒ tailrec(thunk())
    case Return(x)   ⇒ x
  }
}

trait IOActor extends Actor with IO {
  import IOActor._

  private val _messages: mutable.Queue[MessageInvocation] = mutable.Queue.empty

  private var _state: Map[IO.Handle, HandleState] = Map.empty

  private var _continuations: Map[MessageInvocation, IOContinuation[_]] = Map.empty

  private def state(handle: IO.Handle): HandleState = _state.get(handle) match {
    case Some(s) ⇒ s
    case _ ⇒
      val s = new HandleState()
      _state += (handle -> s)
      s
  }

  protected def read(handle: IO.Handle, len: Int): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
    state(handle).messages enqueue self.currentMessage
    _continuations += (self.currentMessage -> ByteStringLength(cont, len))
    run(handle)
  }

  protected def read(handle: IO.Handle): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
    state(handle).messages enqueue self.currentMessage
    _continuations += (self.currentMessage -> ByteStringAny(cont))
    run(handle)
  }

  protected def read(handle: IO.Handle, delimiter: ByteString, inclusive: Boolean = false): ByteString @cps[Any] = shift { cont: (ByteString ⇒ Any) ⇒
    state(handle).messages enqueue self.currentMessage
    _continuations += (self.currentMessage -> ByteStringDelimited(cont, delimiter, inclusive, 0))
    run(handle)
  }

  protected def loop(block: ⇒ Any @cps[Any]): Unit @cps[Any] = {
    def f(): TailRec[Unit] @cps[Any] = {
      block
      Call(() ⇒ f())
    }
    tailrec(f())
  }

  protected def loopWhile(test: ⇒ Boolean)(block: ⇒ Any @cps[Any]): Unit @cps[Any] = {
    def f(): TailRec[Unit] @cps[Any] = {
      if (test) {
        block
        Call(() ⇒ f())
      } else Return(())
    }
    tailrec(f())
  }

  final def receive = {
    case IO.Read(handle, newBytes) ⇒
      val st = state(handle)
      st.readBytes :+= newBytes
      run(handle)
    case msg@IO.Connected(handle) ⇒
      state(handle).connected = true
      if (_receiveIO.isDefinedAt(msg)) reset { _receiveIO(msg) }
    case msg@IO.Closed(handle) ⇒
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
  private def run(handle: IO.Handle): Unit = {
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
      }
    } else {
      while (_continuations.isEmpty && _messages.nonEmpty) {
        self invoke _messages.dequeue
      }
    }
  }
}

class IOManager(bufferSize: Int = 8192) extends Actor {

  var worker: IOWorker = _

  override def preStart: Unit = {
    worker = new IOWorker(self, bufferSize)
    worker.start
  }

  def receive = {
    case IO.Listen(handle, address)  ⇒ worker.createServer(handle, address)
    case IO.Connect(handle, address) ⇒ worker.createClient(handle, address)
    case IO.Accept(handle, source)   ⇒ worker.acceptConnection(handle, source)
    case IO.Write(handle, data)      ⇒ worker.write(handle, data)
    case IO.Close(handle)            ⇒ worker.close(handle)
  }

  override def postStop: Unit = {
    worker.shutdown
  }

}

private[akka] object IOWorker {
  sealed trait ChangeRequest
  case class Register(handle: IO.Handle, channel: SelectableChannel, ops: Int) extends ChangeRequest
  case class Accepted(handle: IO.Handle, serverHandle: IO.Handle) extends ChangeRequest
  case class QueueWrite(handle: IO.Handle, data: ByteBuffer) extends ChangeRequest
  case class Close(handle: IO.Handle) extends ChangeRequest
  case object Shutdown extends ChangeRequest
}

private[akka] class IOWorker(ioManager: ActorRef, val bufferSize: Int) {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import IOWorker._

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  implicit val optionIOManager: Some[ActorRef] = Some(ioManager)

  def createServer(handle: IO.Handle, address: InetSocketAddress): Unit = {
    val server = ServerSocketChannel open ()
    server configureBlocking false
    server.socket bind address
    addChangeRequest(Register(handle, server, OP_ACCEPT))
  }

  def createClient(handle: IO.Handle, address: InetSocketAddress): Unit = {
    val client = SocketChannel open ()
    client configureBlocking false
    client connect address
    addChangeRequest(Register(handle, client, OP_CONNECT | OP_READ))
  }

  def acceptConnection(handle: IO.Handle, source: IO.Handle): Unit =
    addChangeRequest(Accepted(handle, source))

  def write(handle: IO.Handle, data: ByteString): Unit =
    addChangeRequest(QueueWrite(handle, data.asByteBuffer))

  def close(handle: IO.Handle): Unit =
    addChangeRequest(Close(handle))

  def shutdown(): Unit =
    addChangeRequest(Shutdown)

  def start(): Unit =
    thread.start

  // private

  private val selector: Selector = Selector open ()

  private val _changeRequests = new AtomicReference(List.empty[ChangeRequest])

  private var acceptedChannels = Map.empty[IO.Handle, Queue[SelectableChannel]].withDefaultValue(Queue.empty)

  private var channels = Map.empty[IO.Handle, SelectableChannel]

  private var writeQueues = Map.empty[IO.Handle, Queue[ByteBuffer]].withDefaultValue(Queue.empty)

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
        _changeRequests.getAndSet(Nil).reverse foreach {
          case Register(handle, channel, ops) ⇒
            channels += (handle -> channel)
            channel register (selector, ops, handle)
          case Accepted(handle, serverHandle) ⇒
            val (channel, rest) = acceptedChannels(serverHandle).dequeue
            if (rest.isEmpty) acceptedChannels -= serverHandle
            else acceptedChannels += (serverHandle -> rest)
            channels += (handle -> channel)
            channel register (selector, OP_READ, handle)
          case QueueWrite(handle, data) ⇒
            if (channels contains handle) {
              val queue = writeQueues(handle)
              if (queue.isEmpty) addOps(handle, OP_WRITE)
              writeQueues += (handle -> queue.enqueue(data))
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
        case client: SocketChannel ⇒ connected(handle, client)
      }
      if (key.isAcceptable) key.channel match {
        case server: ServerSocketChannel ⇒ accept(handle, server)
      }
      if (key.isReadable) key.channel match {
        case channel: ReadChannel ⇒ read(handle, channel)
      }
      if (key.isWritable) key.channel match {
        case channel: WriteChannel ⇒ write(handle, channel)
      }
    } catch {
      case e: CancelledKeyException ⇒ cleanup(handle)
    }
  }

  private def cleanup(handle: IO.Handle): Unit = {
    acceptedChannels -= handle
    writeQueues -= handle
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

  private def connected(handle: IO.Handle, client: SocketChannel): Unit = {
    client.finishConnect
    removeOps(handle, OP_CONNECT)
    handle.owner ! IO.Connected(handle)
  }

  @tailrec
  private def accept(handle: IO.Handle, server: ServerSocketChannel): Unit = {
    val client = server.accept
    if (client ne null) {
      client configureBlocking false
      acceptedChannels += (handle -> (acceptedChannels(handle) enqueue client))
      handle.owner ! IO.NewConnection(handle)
      accept(handle, server)
    }
  }

  @tailrec
  private def read(handle: IO.Handle, channel: ReadChannel): Unit = {
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
  private def write(handle: IO.Handle, channel: WriteChannel): Unit = {
    val queue = writeQueues(handle)
    if (queue.nonEmpty) {
      val (buf, bufs) = queue.dequeue
      val writeLen = channel write buf
      if (buf.remaining == 0) {
        if (bufs.isEmpty) {
          writeQueues -= handle
          removeOps(handle, OP_WRITE)
        } else {
          writeQueues += (handle -> bufs)
          write(handle, channel)
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
