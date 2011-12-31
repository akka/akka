/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.dispatch.{ Future, ExecutionContext }
import akka.util.ByteString
import java.net.InetSocketAddress
import java.io.IOException
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
import scala.annotation.tailrec
import scala.collection.generic.CanBuildFrom
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

  def listen(address: InetSocketAddress)(implicit system: ActorSystem, owner: ActorRef): ServerHandle = {
    val ioManager = IOManager.start()(system)
    val server = ServerHandle(owner, ioManager)
    ioManager ! Listen(server, address)
    server
  }

  def listen(host: String, port: Int)(implicit system: ActorSystem, owner: ActorRef): ServerHandle =
    listen(new InetSocketAddress(host, port))(system, owner)

  def listen(address: InetSocketAddress, owner: ActorRef)(implicit system: ActorSystem): ServerHandle =
    listen(address)(system, owner)

  def listen(host: String, port: Int, owner: ActorRef)(implicit system: ActorSystem): ServerHandle =
    listen(new InetSocketAddress(host, port))(system, owner)

  def connect(address: InetSocketAddress)(implicit system: ActorSystem, owner: ActorRef): SocketHandle = {
    val ioManager = IOManager.start()(system)
    val socket = SocketHandle(owner, ioManager)
    ioManager ! Connect(socket, address)
    socket
  }

  def connect(host: String, port: Int)(implicit system: ActorSystem, owner: ActorRef): SocketHandle =
    connect(new InetSocketAddress(host, port))(system, owner)

  def connect(address: InetSocketAddress, owner: ActorRef)(implicit system: ActorSystem): SocketHandle =
    connect(address)(system, owner)

  def connect(host: String, port: Int, owner: ActorRef)(implicit system: ActorSystem): SocketHandle =
    connect(new InetSocketAddress(host, port))(system, owner)

  sealed trait Input {
    def ++(that: Input): Input
  }

  object Chunk {
    val empty = Chunk(ByteString.empty)
  }

  case class Chunk(bytes: ByteString) extends Input {
    def ++(that: Input) = that match {
      case Chunk(more) ⇒ Chunk(bytes ++ more)
      case _: EOF      ⇒ that
    }
  }

  case class EOF(cause: Option[Exception]) extends Input {
    def ++(that: Input) = this
  }

  object Iteratee {
    def apply[A](value: A): Iteratee[A] = Done(value)
    def apply(): Iteratee[Unit] = unit
    val unit: Iteratee[Unit] = Done(())
  }

  /**
   * A basic Iteratee implementation of Oleg's Iteratee (http://okmij.org/ftp/Streams.html).
   * No support for Enumerator or Input types other then ByteString at the moment.
   */
  sealed abstract class Iteratee[+A] {

    /**
     * Applies the given input to the Iteratee, returning the resulting Iteratee
     * and the unused Input.
     */
    final def apply(input: Input): (Iteratee[A], Input) = this match {
      case Cont(f) ⇒ f(input)
      case iter    ⇒ (iter, input)
    }

    final def get: A = this(EOF(None))._1 match {
      case Done(value) ⇒ value
      case Cont(_)     ⇒ sys.error("Divergent Iteratee")
      case Failure(e)  ⇒ throw e
    }

    final def flatMap[B](f: A ⇒ Iteratee[B]): Iteratee[B] = this match {
      case Done(value)       ⇒ f(value)
      case Cont(k: Chain[_]) ⇒ Cont(k :+ f)
      case Cont(k)           ⇒ Cont(Chain(k, f))
      case failure: Failure  ⇒ failure
    }

    final def map[B](f: A ⇒ B): Iteratee[B] = this match {
      case Done(value)       ⇒ Done(f(value))
      case Cont(k: Chain[_]) ⇒ Cont(k :+ ((a: A) ⇒ Done(f(a))))
      case Cont(k)           ⇒ Cont(Chain(k, (a: A) ⇒ Done(f(a))))
      case failure: Failure  ⇒ failure
    }

  }

  /**
   * An Iteratee representing a result and the remaining ByteString. Also used to
   * wrap any constants or precalculated values that need to be composed with
   * other Iteratees.
   */
  final case class Done[+A](result: A) extends Iteratee[A]

  /**
   * An Iteratee that still requires more input to calculate it's result.
   */
  final case class Cont[+A](f: Input ⇒ (Iteratee[A], Input)) extends Iteratee[A]

  /**
   * An Iteratee representing a failure to calcualte a result.
   * FIXME: move into 'Cont' as in Oleg's implementation
   */
  final case class Failure(exception: Throwable) extends Iteratee[Nothing]

  object IterateeRef {
    def sync[A](initial: Iteratee[A]): IterateeRefSync[A] = new IterateeRefSync(initial)
    def sync(): IterateeRefSync[Unit] = new IterateeRefSync(Iteratee.unit)

    def async[A](initial: Iteratee[A])(implicit executor: ExecutionContext): IterateeRefAsync[A] = new IterateeRefAsync(initial)
    def async()(implicit executor: ExecutionContext): IterateeRefAsync[Unit] = new IterateeRefAsync(Iteratee.unit)

    class Map[K, V] private (refFactory: ⇒ IterateeRef[V], underlying: mutable.Map[K, IterateeRef[V]] = mutable.Map.empty[K, IterateeRef[V]]) extends mutable.Map[K, IterateeRef[V]] {
      def get(key: K) = Some(underlying.getOrElseUpdate(key, refFactory))
      def iterator = underlying.iterator
      def +=(kv: (K, IterateeRef[V])) = { underlying += kv; this }
      def -=(key: K) = { underlying -= key; this }
      override def empty = new Map[K, V](refFactory)
    }
    object Map {
      def apply[K, V](refFactory: ⇒ IterateeRef[V]): IterateeRef.Map[K, V] = new Map(refFactory)
      def sync[K](): IterateeRef.Map[K, Unit] = new Map(IterateeRef.sync())
      def async[K]()(implicit executor: ExecutionContext): IterateeRef.Map[K, Unit] = new Map(IterateeRef.async())
    }
  }

  /**
   * A mutable reference to an Iteratee. Not thread safe.
   *
   * Designed for use within an Actor.
   *
   * Includes mutable implementations of flatMap, map, and apply which
   * update the internal reference and return Unit.
   */
  trait IterateeRef[A] {
    def flatMap(f: A ⇒ Iteratee[A]): Unit
    def map(f: A ⇒ A): Unit
    def apply(input: Input): Unit
  }

  final class IterateeRefSync[A](initial: Iteratee[A]) extends IterateeRef[A] {
    private var _value: (Iteratee[A], Input) = (initial, Chunk.empty)
    def flatMap(f: A ⇒ Iteratee[A]): Unit = _value = _value match {
      case (iter, chunk @ Chunk(bytes)) if bytes.nonEmpty ⇒ (iter flatMap f)(chunk)
      case (iter, input)                                  ⇒ (iter flatMap f, input)
    }
    def map(f: A ⇒ A): Unit = _value = (_value._1 map f, _value._2)
    def apply(input: Input): Unit = _value = _value._1(_value._2 ++ input)
    def value: (Iteratee[A], Input) = _value
  }

  final class IterateeRefAsync[A](initial: Iteratee[A])(implicit executor: ExecutionContext) extends IterateeRef[A] {
    private var _value: Future[(Iteratee[A], Input)] = Future((initial, Chunk.empty))
    def flatMap(f: A ⇒ Iteratee[A]): Unit = _value = _value map {
      case (iter, chunk @ Chunk(bytes)) if bytes.nonEmpty ⇒ (iter flatMap f)(chunk)
      case (iter, input)                                  ⇒ (iter flatMap f, input)
    }
    def map(f: A ⇒ A): Unit = _value = _value map (v ⇒ (v._1 map f, v._2))
    def apply(input: Input): Unit = _value = _value map (v ⇒ v._1(v._2 ++ input))
    def future: Future[(Iteratee[A], Input)] = _value
  }

  /**
   * An Iteratee that returns the ByteString prefix up until the supplied delimiter.
   * The delimiter is dropped by default, but it can be returned with the result by
   * setting 'inclusive' to be 'true'.
   */
  def takeUntil(delimiter: ByteString, inclusive: Boolean = false): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        val startIdx = bytes.indexOfSlice(delimiter, math.max(taken.length - delimiter.length, 0))
        if (startIdx >= 0) {
          val endIdx = startIdx + delimiter.length
          (Done(bytes take (if (inclusive) endIdx else startIdx)), Chunk(bytes drop endIdx))
        } else {
          (Cont(step(bytes)), Chunk.empty)
        }
      case eof ⇒ (Cont(step(taken)), eof)
    }

    Cont(step(ByteString.empty))
  }

  def takeWhile(p: (Byte) ⇒ Boolean): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val (found, rest) = more span p
        if (rest.isEmpty)
          (Cont(step(taken ++ found)), Chunk.empty)
        else
          (Done(taken ++ found), Chunk(rest))
      case eof ⇒ (Done(taken), eof)
    }

    Cont(step(ByteString.empty))
  }

  /**
   * An Iteratee that returns a ByteString of the requested length.
   */
  def take(length: Int): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        if (bytes.length >= length)
          (Done(bytes.take(length)), Chunk(bytes.drop(length)))
        else
          (Cont(step(bytes)), Chunk.empty)
      case eof ⇒ (Cont(step(taken)), eof)
    }

    Cont(step(ByteString.empty))
  }

  /**
   * An Iteratee that ignores the specified number of bytes.
   */
  def drop(length: Int): Iteratee[Unit] = {
    def step(left: Int)(input: Input): (Iteratee[Unit], Input) = input match {
      case Chunk(more) ⇒
        if (left > more.length)
          (Cont(step(left - more.length)), Chunk.empty)
        else
          (Done(), Chunk(more drop left))
      case eof ⇒ (Done(), eof)
    }

    Cont(step(length))
  }

  /**
   * An Iteratee that returns the remaining ByteString until an EOF is given.
   */
  val takeAll: Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        (Cont(step(bytes)), Chunk.empty)
      case eof ⇒ (Done(taken), eof)
    }

    Cont(step(ByteString.empty))
  }

  /**
   * An Iteratee that returns any input it receives
   */
  val takeAny: Iteratee[ByteString] = Cont {
    case Chunk(bytes) if bytes.nonEmpty ⇒ (Done(bytes), Chunk.empty)
    case Chunk(bytes)                   ⇒ (takeAny, Chunk.empty)
    case eof                            ⇒ (Done(ByteString.empty), eof)
  }

  def takeList[A](length: Int)(iter: Iteratee[A]): Iteratee[List[A]] = {
    def step(left: Int, list: List[A]): Iteratee[List[A]] =
      if (left == 0) Done(list.reverse)
      else iter flatMap (a ⇒ step(left - 1, a :: list))

    step(length, Nil)
  }

  def peek(length: Int): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        if (bytes.length >= length)
          (Done(bytes.take(length)), Chunk(bytes))
        else
          (Cont(step(bytes)), Chunk.empty)
      case eof ⇒ (Cont(step(taken)), eof)
    }

    Cont(step(ByteString.empty))
  }

  def repeat(iter: Iteratee[Unit]): Iteratee[Unit] =
    iter flatMap (_ ⇒ repeat(iter))

  def traverse[A, B, M[A] <: Traversable[A]](in: M[A])(f: A ⇒ Iteratee[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Iteratee[M[B]] =
    fold(cbf(in), in)((b, a) ⇒ f(a) map (b += _)) map (_.result)

  def fold[A, B, M[A] <: Traversable[A]](initial: B, in: M[A])(f: (B, A) ⇒ Iteratee[B]): Iteratee[B] =
    (Iteratee(initial) /: in)((ib, a) ⇒ ib flatMap (b ⇒ f(b, a)))

  // private api

  private object Chain {
    def apply[A](f: Input ⇒ (Iteratee[A], Input)) = new Chain[A](f, Nil, Nil)
    def apply[A, B](f: Input ⇒ (Iteratee[A], Input), k: A ⇒ Iteratee[B]) = new Chain[B](f, List(k.asInstanceOf[Any ⇒ Iteratee[Any]]), Nil)
  }

  /**
   * A function 'ByteString => Iteratee[A]' that composes with 'A => Iteratee[B]' functions
   * in a stack-friendly manner.
   *
   * For internal use within Iteratee.
   */
  private final case class Chain[A] private (cur: Input ⇒ (Iteratee[Any], Input), queueOut: List[Any ⇒ Iteratee[Any]], queueIn: List[Any ⇒ Iteratee[Any]]) extends (Input ⇒ (Iteratee[A], Input)) {

    def :+[B](f: A ⇒ Iteratee[B]) = new Chain[B](cur, queueOut, f.asInstanceOf[Any ⇒ Iteratee[Any]] :: queueIn)

    def apply(input: Input): (Iteratee[A], Input) = {
      @tailrec
      def run(result: (Iteratee[Any], Input), queueOut: List[Any ⇒ Iteratee[Any]], queueIn: List[Any ⇒ Iteratee[Any]]): (Iteratee[Any], Input) = {
        if (queueOut.isEmpty) {
          if (queueIn.isEmpty) result
          else run(result, queueIn.reverse, Nil)
        } else result match {
          case (Done(value), rest) ⇒
            queueOut.head(value) match {
              //case Cont(Chain(f, q)) ⇒ run(f(rest), q ++ tail) <- can cause big slowdown, need to test if needed
              case Cont(f) ⇒ run(f(rest), queueOut.tail, queueIn)
              case iter    ⇒ run((iter, rest), queueOut.tail, queueIn)
            }
          case (Cont(f), rest) ⇒
            (Cont(new Chain(f, queueOut, queueIn)), rest)
          case _ ⇒ result
        }
      }
      run(cur(input), queueOut, queueIn).asInstanceOf[(Iteratee[A], Input)]
    }
  }

}

object IOManager {
  def start()(implicit system: ActorSystem): ActorRef = {
    // TODO: Replace with better "get or create" if/when available
    val ref = system.actorFor(system / "io-manager")
    if (!ref.isInstanceOf[EmptyLocalActorRef]) ref else try {
      system.actorOf(Props[IOManager], "io-manager")
    } catch {
      case _: InvalidActorNameException ⇒ ref
    }
  }
  def stop()(implicit system: ActorSystem): Unit = {
    // TODO: send shutdown message to IOManager
  }
}

final class WriteBuffer(bufferSize: Int) {
  private val _queue = new java.util.ArrayDeque[ByteString]
  private val _buffer = ByteBuffer.allocate(bufferSize)
  private var _length = 0

  private def fillBuffer(): Boolean = {
    while (!_queue.isEmpty && _buffer.hasRemaining) {
      val next = _queue.pollFirst
      val rest = next.drop(next.copyToBuffer(_buffer))
      if (rest.nonEmpty) _queue.offerFirst(rest)
    }
    !_buffer.hasRemaining
  }

  def enqueue(elem: ByteString): this.type = {
    _length += elem.length
    val rest = elem.drop(elem.copyToBuffer(_buffer))
    if (rest.nonEmpty) _queue.offerLast(rest)
    this
  }

  def length = _length

  def isEmpty = _length == 0

  def write(channel: WritableByteChannel with SelectableChannel): Int = {
    @tailrec
    def run(total: Int): Int = {
      if (this.isEmpty) total
      else {
        val written = try {
          _buffer.flip()
          channel write _buffer
        } finally {
          // don't leave buffer in wrong state
          _buffer.compact()
          fillBuffer()
        }
        _length -= written
        if (_buffer.position > 0) {
          total + written
        } else {
          run(total + written)
        }
      }
    }

    run(0)
  }

}

// TODO: Support a pool of workers
final class IOManager extends Actor {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }

  val bufferSize = 8192 // TODO: make buffer size configurable

  type ReadChannel = ReadableByteChannel with SelectableChannel
  type WriteChannel = WritableByteChannel with SelectableChannel

  val selector: Selector = Selector open ()

  val channels = mutable.Map.empty[IO.Handle, SelectableChannel]

  val accepted = mutable.Map.empty[IO.ServerHandle, mutable.Queue[SelectableChannel]]

  val writes = mutable.Map.empty[IO.WriteHandle, WriteBuffer]

  val closing = mutable.Set.empty[IO.Handle]

  val buffer = ByteBuffer.allocate(bufferSize)

  var lastSelect = 0

  val selectAt = 100 // TODO: determine best value, perhaps based on throughput? Other triggers (like write queue size)?

  //val selectEveryNanos = 1000000 // nanos

  //var lastSelectNanos = System.nanoTime

  var running = false

  var selectSent = false

  var fastSelect = false

  object Select

  def run() {
    if (!running) {
      running = true
      if (!selectSent) {
        selectSent = true
        self ! Select
      }
    }
    lastSelect += 1
    if (lastSelect >= selectAt /* || (lastSelectNanos + selectEveryNanos) < System.nanoTime */ ) select()
  }

  def select() {
    if (selector.isOpen) {
      // TODO: Make select behaviour configurable.
      // Blocking 1ms reduces allocations during idle times, non blocking gives better performance.
      if (fastSelect) selector.selectNow else selector.select(1)
      val keys = selector.selectedKeys.iterator
      fastSelect = keys.hasNext
      while (keys.hasNext) {
        val key = keys.next()
        keys.remove()
        if (key.isValid) { process(key) }
      }
      if (channels.isEmpty) running = false
    } else {
      running = false
    }
    //lastSelectNanos = System.nanoTime
    lastSelect = 0
  }

  def receive = {
    case Select ⇒
      select()
      if (running) self ! Select
      selectSent = running

    case IO.Listen(server, address) ⇒
      val channel = ServerSocketChannel open ()
      channel configureBlocking false
      channel.socket bind (address, 1000) // TODO: make backlog configurable
      channels update (server, channel)
      channel register (selector, OP_ACCEPT, server)
      run()

    case IO.Connect(socket, address) ⇒
      val channel = SocketChannel open ()
      channel configureBlocking false
      channel connect address
      channels update (socket, channel)
      channel register (selector, OP_CONNECT | OP_READ, socket)
      run()

    case IO.Accept(socket, server) ⇒
      val queue = accepted(server)
      val channel = queue.dequeue()
      channels update (socket, channel)
      channel register (selector, OP_READ, socket)
      run()

    case IO.Write(handle, data) ⇒
      if (channels contains handle) {
        val queue = {
          val existing = writes get handle
          if (existing.isDefined) existing.get
          else {
            val q = new WriteBuffer(bufferSize)
            writes update (handle, q)
            q
          }
        }
        if (queue.isEmpty) addOps(handle, OP_WRITE)
        queue enqueue data
        if (queue.length >= bufferSize) write(handle, channels(handle).asInstanceOf[WriteChannel])
      }
      run()

    case IO.Close(handle: IO.WriteHandle) ⇒
      if (writes get handle filterNot (_.isEmpty) isDefined) {
        closing += handle
      } else {
        cleanup(handle, None)
      }
      run()

    case IO.Close(handle) ⇒
      cleanup(handle, None)
      run()
  }

  override def postStop {
    channels.keys foreach (handle ⇒ cleanup(handle, None))
    selector.close
  }

  def process(key: SelectionKey) {
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

  def cleanup(handle: IO.Handle, cause: Option[Exception]) {
    closing -= handle
    handle match {
      case server: IO.ServerHandle  ⇒ accepted -= server
      case writable: IO.WriteHandle ⇒ writes -= writable
    }
    channels.get(handle) match {
      case Some(channel) ⇒
        channel.close
        channels -= handle
        if (!handle.owner.isTerminated) handle.owner ! IO.Closed(handle, cause)
      case None ⇒
    }
  }

  def setOps(handle: IO.Handle, ops: Int): Unit =
    channels(handle) keyFor selector interestOps ops

  def addOps(handle: IO.Handle, ops: Int) {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur | ops)
  }

  def removeOps(handle: IO.Handle, ops: Int) {
    val key = channels(handle) keyFor selector
    val cur = key.interestOps
    key interestOps (cur - (cur & ops))
  }

  def connect(socket: IO.SocketHandle, channel: SocketChannel) {
    if (channel.finishConnect) {
      removeOps(socket, OP_CONNECT)
      socket.owner ! IO.Connected(socket)
    } else {
      cleanup(socket, None) // TODO: Add a cause
    }
  }

  @tailrec
  def accept(server: IO.ServerHandle, channel: ServerSocketChannel) {
    val socket = channel.accept
    if (socket ne null) {
      socket configureBlocking false
      val queue = {
        val existing = accepted get server
        if (existing.isDefined) existing.get
        else {
          val q = mutable.Queue[SelectableChannel]()
          accepted update (server, q)
          q
        }
      }
      queue += socket
      server.owner ! IO.NewClient(server)
      accept(server, channel)
    }
  }

  @tailrec
  def read(handle: IO.ReadHandle, channel: ReadChannel) {
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

  def write(handle: IO.WriteHandle, channel: WriteChannel) {
    val queue = writes(handle)
    queue write channel
    if (queue.isEmpty) {
      if (closing(handle)) {
        cleanup(handle, None)
      } else {
        removeOps(handle, OP_WRITE)
      }
    }
  }

}
