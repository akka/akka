/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.higherKinds
import language.postfixOps

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import akka.util.ByteString
import java.net.{ SocketAddress, InetSocketAddress }
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
import java.util.UUID
import java.io.{ EOFException, IOException }
import akka.actor.IOManager.Settings
import akka.actor.IO.Chunk

/**
 * IO messages and iteratees.
 *
 * This is still in an experimental state and is subject to change until it
 * has received more real world testing.
 */
@deprecated("use the new implementation in package akka.io instead", "2.2")
object IO {

  final class DivergentIterateeException extends IllegalStateException("Iteratees should not return a continuation when receiving EOF")

  /**
   * An immutable handle to a Java NIO Channel. Contains a reference to the
   * [[akka.actor.ActorRef]] that will receive events related to the Channel,
   * a reference to the [[akka.actor.IOManager]] that manages the Channel, and
   * a [[java.util.UUID]] to uniquely identify the Channel.
   */
  sealed trait Handle {
    this: Product ⇒
    def owner: ActorRef
    def ioManager: ActorRef
    def uuid: UUID
    override lazy val hashCode = scala.runtime.ScalaRunTime._hashCode(this)

    def asReadable: ReadHandle = throw new ClassCastException(this.toString + " is not a ReadHandle")
    def asWritable: WriteHandle = throw new ClassCastException(this.toString + " is not a WriteHandle")
    def asSocket: SocketHandle = throw new ClassCastException(this.toString + " is not a SocketHandle")
    def asServer: ServerHandle = throw new ClassCastException(this.toString + " is not a ServerHandle")

    /**
     * Sends a request to the [[akka.actor.IOManager]] to close the Channel
     * associated with this [[akka.actor.IO.Handle]].
     *
     * This can also be performed by sending [[akka.actor.IO.Close]] to the
     * [[akka.actor.IOManager]].
     */
    def close(): Unit = ioManager ! Close(this)
  }

  /**
   * A [[akka.actor.IO.Handle]] to a ReadableByteChannel.
   */
  sealed trait ReadHandle extends Handle with Product {
    override def asReadable: ReadHandle = this
  }

  /**
   * A [[akka.actor.IO.Handle]] to a WritableByteChannel.
   */
  sealed trait WriteHandle extends Handle with Product {
    override def asWritable: WriteHandle = this

    /**
     * Sends a request to the [[akka.actor.IOManager]] to write to the
     * Channel associated with this [[akka.actor.IO.Handle]].
     *
     * This can also be performed by sending [[akka.actor.IO.Write]] to the
     * [[akka.actor.IOManager]].
     */
    def write(bytes: ByteString): Unit = ioManager ! Write(this, bytes)
  }

  /**
   * A [[akka.actor.IO.Handle]] to a SocketChannel. Instances are normally
   * created by [[akka.actor.IOManager]].connect() and
   * [[akka.actor.IO.ServerHandle]].accept().
   */
  case class SocketHandle(owner: ActorRef, ioManager: ActorRef, uuid: UUID = UUID.randomUUID()) extends ReadHandle with WriteHandle {
    override def asSocket: SocketHandle = this
  }

  /**
   * A [[akka.actor.IO.Handle]] to a ServerSocketChannel. Instances are
   * normally created by [[akka.actor.IOManager]].listen().
   */
  case class ServerHandle(owner: ActorRef, ioManager: ActorRef, uuid: UUID = UUID.randomUUID()) extends Handle {
    override def asServer: ServerHandle = this

    /**
     * Sends a request to the [[akka.actor.IOManager]] to accept an incoming
     * connection to the ServerSocketChannel associated with this
     * [[akka.actor.IO.Handle]].
     *
     * This can also be performed by creating a new [[akka.actor.IO.SocketHandle]]
     * and sending it within an [[akka.actor.IO.Accept]] to the [[akka.actor.IOManager]].
     *
     * @param options Seq of [[akka.actor.IO.SocketOptions]] to set on accepted socket
     * @param socketOwner the [[akka.actor.ActorRef]] that should receive events
     *                    associated with the SocketChannel. The ActorRef for the
     *                    current Actor will be used implicitly.
     * @return a new SocketHandle that can be used to perform actions on the
     *         new connection's SocketChannel.
     */
    def accept(options: immutable.Seq[SocketOption] = Nil)(implicit socketOwner: ActorRef): SocketHandle = {
      val socket = SocketHandle(socketOwner, ioManager)
      ioManager ! Accept(socket, this, options)
      socket
    }
  }

  /**
   * Options to be set when setting up a [[akka.actor.IO.SocketHandle]]
   */
  sealed trait SocketOption

  /**
   * Options to be set when setting up a [[akka.actor.IO.ServerHandle]]
   */
  sealed trait ServerSocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to enable or disable SO_KEEPALIVE
   *
   * For more information see [[java.net.Socket.setKeepAlive]]
   */
  case class KeepAlive(on: Boolean) extends SocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to enable or disable OOBINLINE (receipt
   * of TCP urgent data) By default, this option is disabled and TCP urgent
   * data received on a [[akka.actor.IO.SocketHandle]] is silently discarded.
   *
   * For more information see [[java.net.Socket.setOOBInline]]
   */
  case class OOBInline(on: Boolean) extends SocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to set performance preferences for this
   * [[akka.actor.IO.SocketHandle]].
   *
   * For more information see [[java.net.Socket.setPerformancePreferences]]
   */
  case class PerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int) extends SocketOption with ServerSocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to set the SO_RCVBUF option for this
   * [[akka.actor.IO.SocketHandle]].
   *
   * For more information see [[java.net.Socket.setReceiveBufferSize]]
   */
  case class ReceiveBufferSize(size: Int) extends SocketOption with ServerSocketOption {
    require(size > 0, "Receive buffer size must be greater than 0")
  }

  /**
   * [[akka.actor.IO.SocketOption]] to enable or disable SO_REUSEADDR
   *
   * For more information see [[java.net.Socket.setReuseAddress]]
   */
  case class ReuseAddress(on: Boolean) extends SocketOption with ServerSocketOption

  /**
   * [[akka.actor.IO.ServerSocketOption]] to set the maximum backlog of connections. 0 or negative means that the platform default will be used.
   * For more information see [[http://docs.oracle.com/javase/7/docs/api/java/nio/channels/ServerSocketChannel.html#bind(java.net.SocketAddress, int)]]
   * @param numberOfConnections
   */
  case class Backlog(numberOfConnections: Int) extends ServerSocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to set the SO_SNDBUF option for this
   * [[akka.actor.IO.SocketHandle]].
   *
   * For more information see [[java.net.Socket.setSendBufferSize]]
   */
  case class SendBufferSize(size: Int) extends SocketOption {
    require(size > 0, "Send buffer size must be greater than 0")
  }

  /**
   * [[akka.actor.IO.SocketOption]] to enable or disable SO_LINGER with the
   * specified linger time in seconds.
   *
   * For more information see [[java.net.Socket.setSoLinger]]
   */
  case class SoLinger(linger: Option[Int]) extends SocketOption {
    if (linger.isDefined) require(linger.get >= 0, "linger must not be negative if on")
  }

  /**
   * [[akka.actor.IO.SocketOption]] to set SO_TIMEOUT to the specified
   * timeout rounded down to the nearest millisecond. A timeout of
   * zero is treated as infinant.
   *
   * For more information see [[java.net.Socket.setSoTimeout]]
   */
  case class SoTimeout(timeout: Duration) extends SocketOption {
    require(timeout.toMillis >= 0, "SoTimeout must be >= 0ms")
  }

  /**
   * [[akka.actor.IO.SocketOption]] to enable or disable TCP_NODELAY
   * (disable or enable Nagle's algorithm)
   *
   * For more information see [[java.net.Socket.setTcpNoDelay]]
   */
  case class TcpNoDelay(on: Boolean) extends SocketOption

  /**
   * [[akka.actor.IO.SocketOption]] to set the traffic class or
   * type-of-service octet in the IP header for packets sent from this
   * [[akka.actor.IO.SocketHandle]].
   *
   * For more information see [[java.net.Socket.setTrafficClass]]
   */
  case class TrafficClass(tc: Int) extends SocketOption {
    require(tc >= 0, "Traffic class must be >= 0")
    require(tc <= 255, "Traffic class must be <= 255")
  }

  /**
   * Messages used to communicate with an [[akka.actor.IOManager]].
   */
  sealed trait IOMessage

  /**
   * Message to an [[akka.actor.IOManager]] to create a ServerSocketChannel
   * listening on the provided address with the given
   * [[akka.actor.IO.ServerSocketOption]]s.
   *
   * Normally sent using IOManager.listen()
   */
  case class Listen(server: ServerHandle, address: SocketAddress, options: immutable.Seq[ServerSocketOption] = Nil) extends IOMessage

  /**
   * Message from an [[akka.actor.IOManager]] that the ServerSocketChannel is
   * now listening for connections.
   *
   * No action is required by the receiving [[akka.actor.Actor]].
   */
  case class Listening(server: ServerHandle, address: SocketAddress) extends IOMessage

  /**
   * Message from an [[akka.actor.IOManager]] that a new connection has been
   * made to the ServerSocketChannel and needs to be accepted.
   */
  case class NewClient(server: ServerHandle) extends IOMessage

  /**
   * Message to an [[akka.actor.IOManager]] to accept a new connection with the
   * given [[akka.actor.IO.SocketOption]]s.
   *
   * Normally sent using [[akka.actor.IO.ServerHandle]].accept()
   */
  case class Accept(socket: SocketHandle, server: ServerHandle, options: immutable.Seq[SocketOption] = Nil) extends IOMessage

  /**
   * Message to an [[akka.actor.IOManager]] to create a SocketChannel connected
   * to the provided address with the given [[akka.actor.IO.SocketOption]]s.
   *
   * Normally sent using IOManager.connect()
   */
  case class Connect(socket: SocketHandle, address: SocketAddress, options: immutable.Seq[SocketOption] = Nil) extends IOMessage

  /**
   * Message from an [[akka.actor.IOManager]] that the SocketChannel has
   * successfully connected.
   *
   * No action is required by the receiving [[akka.actor.Actor]].
   */
  case class Connected(socket: SocketHandle, address: SocketAddress) extends IOMessage

  /**
   * Message to an [[akka.actor.IOManager]] to close the Channel.
   *
   * Normally sent using [[akka.actor.IO.Handle]].close()
   */
  case class Close(handle: Handle) extends IOMessage

  /**
   * Message from an [[akka.actor.IOManager]] that the Channel has closed. Can
   * optionally contain the Exception that caused the Channel to close, if
   * applicable.
   *
   * No action is required by the receiving [[akka.actor.Actor]].
   */
  case class Closed(handle: Handle, cause: Input) extends IOMessage

  /**
   * Message from an [[akka.actor.IOManager]] that contains bytes read from
   * the SocketChannel.
   */
  case class Read(handle: ReadHandle, bytes: ByteString) extends IOMessage

  /**
   * Message to an [[akka.actor.IOManager]] to write to the SocketChannel.
   *
   * Normally sent using [[akka.actor.IO.SocketHandle]].write()
   */
  case class Write(handle: WriteHandle, bytes: ByteString) extends IOMessage

  /**
   * Represents part of a stream of bytes that can be processed by an
   * [[akka.actor.IO.Iteratee]].
   */
  sealed trait Input {
    /**
     * Append another Input to this one.
     *
     * If 'that' is an [[akka.actor.IO.EOF]] then it will replace any
     * remaining bytes in this Input. If 'this' is an [[akka.actor.IO.EOF]]
     * then it will be replaced by 'that'.
     */
    def ++(that: Input): Input
  }

  object Chunk {
    /**
     * Represents the empty Chunk
     */
    val empty: Chunk = new Chunk(ByteString.empty)
  }

  /**
   * Part of an [[akka.actor.IO.Input]] stream that contains a chunk of bytes.
   */
  case class Chunk(bytes: ByteString) extends Input {
    final override def ++(that: Input): Input = that match {
      case c @ Chunk(more) ⇒
        if (more.isEmpty) this
        else if (bytes.isEmpty) c
        else Chunk(bytes ++ more)
      case other ⇒ other
    }
  }

  /**
   * Part of an [[akka.actor.IO.Input]] stream that represents the end of the
   * stream.
   *
   * This will cause the [[akka.actor.IO.Iteratee]] that processes it
   * to terminate early.
   */
  case object EOF extends Input { final override def ++(that: Input): Input = that }

  /**
   * Part of an [[akka.actor.IO.Input]] stream that represents an error in the stream.
   *
   * This will cause the [[akka.actor.IO.Iteratee]] that processes it
   * to terminate early.
   */
  case class Error(cause: Throwable) extends Input { final override def ++(that: Input): Input = that }

  object Iteratee {
    /**
     * Wrap the provided value within a [[akka.actor.IO.Done]]
     * [[akka.actor.IO.Iteratee]]. This is a helper for cases where the type should be
     * inferred as an Iteratee and not as a Done.
     */
    def apply[A](value: A): Iteratee[A] = Done(value)

    /**
     * Returns Iteratee.unit
     */
    def apply(): Iteratee[Unit] = unit

    /**
     * The single value representing Done(())
     */
    val unit: Iteratee[Unit] = Done(())
  }

  /**
   * A basic Iteratee implementation of Oleg's Iteratee (http://okmij.org/ftp/Streams.html).
   * To keep this implementation simple it has no support for Enumerator or Input types
   * other then ByteString.
   *
   * Other Iteratee implementations can be used in place of this one if any
   * missing features are required.
   */
  sealed abstract class Iteratee[+A] {

    /**
     * Processes the given [[akka.actor.IO.Input]], returning the resulting
     * Iteratee and the remaining Input.
     */
    final def apply(input: Input): (Iteratee[A], Input) = this match {
      case Next(f) ⇒ f(input)
      case iter    ⇒ (iter, input)
    }

    /**
     * Passes an [[akka.actor.IO.EOF]] to this Iteratee and returns the
     * result if available.
     *
     * If this Iteratee is in a failure state then the Exception will be thrown.
     *
     * If this Iteratee is not well behaved (does not return a result on EOF)
     * then a "Divergent Iteratee" Exception will be thrown.
     */
    final def get: A = this(EOF)._1 match {
      case Done(value) ⇒ value
      case Next(_)     ⇒ throw new DivergentIterateeException
      case Failure(t)  ⇒ throw t
    }

    /**
     * Applies a function to the result of this Iteratee, resulting in a new
     * Iteratee. Any unused [[akka.actor.IO.Input]] that is given to this
     * Iteratee will be passed to that resulting Iteratee. This is the
     * primary method of composing Iteratees together in order to process
     * an Input stream.
     */
    final def flatMap[B](f: A ⇒ Iteratee[B]): Iteratee[B] = this match {
      case Done(value)       ⇒ f(value)
      case Next(k: Chain[_]) ⇒ Next(k :+ f)
      case Next(k)           ⇒ Next(Chain(k, f))
      case f: Failure        ⇒ f
    }

    /**
     * Applies a function to transform the result of this Iteratee.
     */
    final def map[B](f: A ⇒ B): Iteratee[B] = this match {
      case Done(value)       ⇒ Done(f(value))
      case Next(k: Chain[_]) ⇒ Next(k :+ ((a: A) ⇒ Done(f(a))))
      case Next(k)           ⇒ Next(Chain(k, (a: A) ⇒ Done(f(a))))
      case f: Failure        ⇒ f
    }
  }

  /**
   * An Iteratee representing a result, usually returned by the successful
   * completion of an Iteratee. Also used to wrap any constants or
   * precalculated values that need to be composed with other Iteratees.
   */
  final case class Done[+A](result: A) extends Iteratee[A]

  /**
   * An [[akka.actor.IO.Iteratee]] that still requires more input to calculate
   * it's result.
   */
  final case class Next[+A](f: Input ⇒ (Iteratee[A], Input)) extends Iteratee[A]

  /**
   * An [[akka.actor.IO.Iteratee]] that represents an erronous end state.
   */
  final case class Failure(cause: Throwable) extends Iteratee[Nothing]

  //FIXME general description of what an IterateeRef is and how it is used, potentially with link to docs
  object IterateeRef {

    /**
     * Creates an [[akka.actor.IO.IterateeRefSync]] containing an initial
     * [[akka.actor.IO.Iteratee]].
     */
    def sync[A](initial: Iteratee[A]): IterateeRefSync[A] = new IterateeRefSync(initial)

    /**
     * Creates an empty [[akka.actor.IO.IterateeRefSync]].
     */
    def sync(): IterateeRefSync[Unit] = new IterateeRefSync(Iteratee.unit)

    /**
     * Creates an [[akka.actor.IO.IterateeRefAsync]] containing an initial
     * [[akka.actor.IO.Iteratee]].
     */
    def async[A](initial: Iteratee[A])(implicit executor: ExecutionContext): IterateeRefAsync[A] = new IterateeRefAsync(initial)

    /**
     * Creates an empty [[akka.actor.IO.IterateeRefAsync]].
     */
    def async()(implicit executor: ExecutionContext): IterateeRefAsync[Unit] = new IterateeRefAsync(Iteratee.unit)

    /**
     * A mutable Map to contain multiple IterateeRefs.
     *
     * This Map differs from the mutable Map within Scala's standard library
     * by automatically including any keys used to lookup an IterateeRef. The
     * 'refFactory' is used to provide the default value for new keys.
     */
    class Map[K, V] private (refFactory: ⇒ IterateeRef[V], underlying: mutable.Map[K, IterateeRef[V]] = mutable.Map.empty[K, IterateeRef[V]]) extends mutable.Map[K, IterateeRef[V]] {
      override def get(key: K) = Some(underlying.getOrElseUpdate(key, refFactory))
      override def iterator = underlying.iterator
      override def +=(kv: (K, IterateeRef[V])) = { underlying += kv; this }
      override def -=(key: K) = { underlying -= key; this }
      override def empty = new Map[K, V](refFactory)
    }

    //FIXME general description of what an Map is and how it is used, potentially with link to docs
    object Map {
      /**
       * Uses a factory to create the initial IterateeRef for each new key.
       */
      def apply[K, V](refFactory: ⇒ IterateeRef[V]): IterateeRef.Map[K, V] = new Map(refFactory)

      /**
       * Creates an empty [[akka.actor.IO.IterateeRefSync]] for each new key.
       */
      def sync[K](): IterateeRef.Map[K, Unit] = new Map(IterateeRef.sync())

      /**
       * Creates an empty [[akka.actor.IO.IterateeRefAsync]] for each new key.
       */
      def async[K]()(implicit executor: ExecutionContext): IterateeRef.Map[K, Unit] = new Map(IterateeRef.async())
    }
  }

  /**
   * A mutable reference to an Iteratee designed for use within an Actor.
   *
   * See [[akka.actor.IO.IterateeRefSync]] and [[akka.actor.IO.IterateeRefAsync]]
   * for details.
   */
  trait IterateeRef[A] {
    //FIXME Add docs
    def flatMap(f: A ⇒ Iteratee[A]): Unit
    //FIXME Add docs
    def map(f: A ⇒ A): Unit
    //FIXME Add docs
    def apply(input: Input): Unit
  }

  /**
   * A mutable reference to an [[akka.actor.IO.Iteratee]]. Not thread safe.
   *
   * Designed for use within an [[akka.actor.Actor]].
   *
   * Includes mutable implementations of flatMap, map, and apply which
   * update the internal reference and return Unit.
   *
   * [[akka.actor.IO.Input]] remaining after processing the Iteratee will
   * be stored and processed later when 'flatMap' is used next.
   */
  final class IterateeRefSync[A](initial: Iteratee[A]) extends IterateeRef[A] {
    private var _value: (Iteratee[A], Input) = (initial, Chunk.empty)
    override def flatMap(f: A ⇒ Iteratee[A]): Unit = _value = _value match {
      case (iter, chunk @ Chunk(bytes)) if bytes.nonEmpty ⇒ (iter flatMap f)(chunk)
      case (iter, input)                                  ⇒ (iter flatMap f, input)
    }
    override def map(f: A ⇒ A): Unit = _value = (_value._1 map f, _value._2)
    override def apply(input: Input): Unit = _value = _value._1(_value._2 ++ input)

    /**
     * Returns the current value of this IterateeRefSync
     */
    def value: (Iteratee[A], Input) = _value
  }

  /**
   * A mutable reference to an [[akka.actor.IO.Iteratee]]. Not thread safe.
   *
   * Designed for use within an [[akka.actor.Actor]], although all actions
   * perfomed on the Iteratee are processed within a [[scala.concurrent.Future]]
   * so it is not safe to refer to the Actor's state from within this Iteratee.
   * Messages should instead be sent to the Actor in order to modify state.
   *
   * Includes mutable implementations of flatMap, map, and apply which
   * update the internal reference and return Unit.
   *
   * [[akka.actor.IO.Input]] remaining after processing the Iteratee will
   * be stored and processed later when 'flatMap' is used next.
   */
  final class IterateeRefAsync[A](initial: Iteratee[A])(implicit executor: ExecutionContext) extends IterateeRef[A] {
    private var _value: Future[(Iteratee[A], Input)] = Future((initial, Chunk.empty))
    override def flatMap(f: A ⇒ Iteratee[A]): Unit = _value = _value map {
      case (iter, chunk @ Chunk(bytes)) if bytes.nonEmpty ⇒ (iter flatMap f)(chunk)
      case (iter, input)                                  ⇒ (iter flatMap f, input)
    }
    override def map(f: A ⇒ A): Unit = _value = _value map (v ⇒ (v._1 map f, v._2))
    override def apply(input: Input): Unit = _value = _value map (v ⇒ v._1(v._2 ++ input))

    /**
     * Returns a Future which will hold the future value of this IterateeRefAsync
     */
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
          (Next(step(bytes)), Chunk.empty)
        }
      case EOF              ⇒ (Failure(new EOFException("Unexpected EOF")), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(ByteString.empty))
  }

  /**
   * An Iteratee that will collect bytes as long as a predicate is true.
   */
  def takeWhile(p: (Byte) ⇒ Boolean): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val (found, rest) = more span p
        if (rest.isEmpty)
          (Next(step(taken ++ found)), Chunk.empty)
        else
          (Done(taken ++ found), Chunk(rest))
      case EOF              ⇒ (Failure(new EOFException("Unexpected EOF")), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(ByteString.empty))
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
          (Next(step(bytes)), Chunk.empty)
      case EOF              ⇒ (Failure(new EOFException("Unexpected EOF")), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(ByteString.empty))
  }

  /**
   * An Iteratee that ignores the specified number of bytes.
   */
  def drop(length: Int): Iteratee[Unit] = {
    def step(left: Int)(input: Input): (Iteratee[Unit], Input) = input match {
      case Chunk(more) ⇒
        if (left > more.length)
          (Next(step(left - more.length)), Chunk.empty)
        else
          (Done(()), Chunk(more drop left))
      case EOF              ⇒ (Failure(new EOFException("Unexpected EOF")), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(length))
  }

  /**
   * An Iteratee that returns the remaining ByteString until an EOF is given.
   */
  val takeAll: Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        (Next(step(bytes)), Chunk.empty)
      case EOF              ⇒ (Done(taken), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(ByteString.empty))
  }

  /**
   * An Iteratee that returns any input it receives
   */
  val takeAny: Iteratee[ByteString] = Next {
    case Chunk(bytes) if bytes.nonEmpty ⇒ (Done(bytes), Chunk.empty)
    case Chunk(bytes)                   ⇒ (takeAny, Chunk.empty)
    case EOF                            ⇒ (Done(ByteString.empty), EOF)
    case e @ Error(cause)               ⇒ (Failure(cause), e)
  }

  /**
   * An Iteratee that creates a list made up of the results of an Iteratee.
   */
  def takeList[A](length: Int)(iter: Iteratee[A]): Iteratee[List[A]] = {
    def step(left: Int, list: List[A]): Iteratee[List[A]] =
      if (left == 0) Done(list.reverse)
      else iter flatMap (a ⇒ step(left - 1, a :: list))

    step(length, Nil)
  }

  /**
   * An Iteratee that returns a [[akka.util.ByteString]] of the request length,
   * but does not consume the Input.
   */
  def peek(length: Int): Iteratee[ByteString] = {
    def step(taken: ByteString)(input: Input): (Iteratee[ByteString], Input) = input match {
      case Chunk(more) ⇒
        val bytes = taken ++ more
        if (bytes.length >= length)
          (Done(bytes.take(length)), Chunk(bytes))
        else
          (Next(step(bytes)), Chunk.empty)
      case EOF              ⇒ (Done(taken), EOF)
      case e @ Error(cause) ⇒ (Failure(cause), e)
    }

    Next(step(ByteString.empty))
  }

  /**
   * An Iteratee that continually repeats an Iteratee.
   */
  def repeat[T](iter: Iteratee[T]): Iteratee[T] = iter flatMap (_ ⇒ repeat(iter))

  /**
   * An Iteratee that applies an Iteratee to each element of a Traversable
   * and finally returning a single Iteratee containing a Traversable of the results.
   */
  def traverse[A, B, M[A] <: Traversable[A]](in: M[A])(f: A ⇒ Iteratee[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]]): Iteratee[M[B]] =
    fold(cbf(in), in)((b, a) ⇒ f(a) map (b += _)) map (_.result)

  /**
   * An Iteratee that folds over a Traversable by applying a function that
   * returns an Iteratee.
   */
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
              case Next(f) ⇒ run(f(rest), queueOut.tail, queueIn)
              case iter    ⇒ run((iter, rest), queueOut.tail, queueIn)
            }
          case (Next(f), rest) ⇒ (Next(new Chain(f, queueOut, queueIn)), rest)
          case _               ⇒ result
        }
      }
      run(cur(input), queueOut, queueIn).asInstanceOf[(Iteratee[A], Input)]
    }
  }

}

/**
 * IOManager contains a reference to the [[akka.actor.IOManagerActor]] for
 * an [[akka.actor.ActorSystem]].
 *
 * This is the recommended entry point to creating sockets for performing
 * IO.
 *
 * Use the companion object to retrieve the instance of this class for an
 * ActorSystem.
 *
 * {{{
 * val ioManager = IOManager(context.system)
 * val socket = ioManager.connect("127.0.0.1")
 * }}}
 *
 * An IOManager does not need to be manually stopped when not in use as it will
 * automatically enter an idle state when it has no channels to manage.
 */
final class IOManager private (system: ExtendedActorSystem) extends Extension { //FIXME how about taking an ActorNextext
  val settings: Settings = {
    val c = system.settings.config.getConfig("akka.io")
    Settings(
      readBufferSize = {
        val sz = c.getBytes("read-buffer-size")
        require(sz <= Int.MaxValue && sz > 0)
        sz.toInt
      },
      selectInterval = c.getInt("select-interval"),
      defaultBacklog = c.getInt("default-backlog"))
  }
  /**
   * A reference to the [[akka.actor.IOManagerActor]] that performs the actual
   * IO. It communicates with other actors using subclasses of
   * [[akka.actor.IO.IOMessage]].
   */
  val actor: ActorRef = system.actorOf(Props(new IOManagerActor(settings)), "io-manager")

  /**
   * Create a ServerSocketChannel listening on an address. Messages will be
   * sent from the [[akka.actor.IOManagerActor]] to the owner
   * [[akka.actor.ActorRef]].
   *
   * @param address the address to listen on
   * @param owner the ActorRef that will receive messages from the IOManagerActor
   * @param option Seq of [[akka.actor.IO.ServerSocketOptions]] to setup on socket
   * @return a [[akka.actor.IO.ServerHandle]] to uniquely identify the created socket
   */
  @deprecated("use the new implementation in package akka.io instead", "2.2")
  def listen(address: SocketAddress, options: immutable.Seq[IO.ServerSocketOption])(implicit owner: ActorRef): IO.ServerHandle = {
    val server = IO.ServerHandle(owner, actor)
    actor ! IO.Listen(server, address, options)
    server
  }

  /**
   * Create a ServerSocketChannel listening on an address. Messages will be
   * sent from the [[akka.actor.IOManagerActor]] to the owner
   * [[akka.actor.ActorRef]].
   *
   * @param address the address to listen on
   * @param owner the ActorRef that will receive messages from the IOManagerActor
   * @return a [[akka.actor.IO.ServerHandle]] to uniquely identify the created socket
   */
  @deprecated("use the new implementation in package akka.io instead", "2.2")
  def listen(address: SocketAddress)(implicit owner: ActorRef): IO.ServerHandle = listen(address, Nil)

  /**
   * Create a ServerSocketChannel listening on a host and port. Messages will
   * be sent from the [[akka.actor.IOManagerActor]] to the owner
   * [[akka.actor.ActorRef]].
   *
   * @param host the hostname or IP to listen on
   * @param port the port to listen on
   * @param options Seq of [[akka.actor.IO.ServerSocketOption]] to setup on socket
   * @param owner the ActorRef that will receive messages from the IOManagerActor
   * @return a [[akka.actor.IO.ServerHandle]] to uniquely identify the created socket
   */
  @deprecated("use the new implementation in package akka.io instead", "2.2")
  def listen(host: String, port: Int, options: immutable.Seq[IO.ServerSocketOption] = Nil)(implicit owner: ActorRef): IO.ServerHandle =
    listen(new InetSocketAddress(host, port), options)(owner)

  /**
   * Create a SocketChannel connecting to an address. Messages will be
   * sent from the [[akka.actor.IOManagerActor]] to the owner
   * [[akka.actor.ActorRef]].
   *
   * @param address the address to connect to
   * @param options Seq of [[akka.actor.IO.SocketOption]] to setup on established socket
   * @param owner the ActorRef that will receive messages from the IOManagerActor
   * @return a [[akka.actor.IO.SocketHandle]] to uniquely identify the created socket
   */
  @deprecated("use the new implementation in package akka.io instead", "2.2")
  def connect(address: SocketAddress, options: immutable.Seq[IO.SocketOption] = Nil)(implicit owner: ActorRef): IO.SocketHandle = {
    val socket = IO.SocketHandle(owner, actor)
    actor ! IO.Connect(socket, address, options)
    socket
  }

  /**
   * Create a SocketChannel connecting to a host and port. Messages will
   * be sent from the [[akka.actor.IOManagerActor]] to the owner
   * [[akka.actor.ActorRef]].
   *
   * @param host the hostname or IP to connect to
   * @param port the port to connect to
   * @param options Seq of [[akka.actor.IO.SocketOption]] to setup on established socket
   * @param owner the ActorRef that will receive messages from the IOManagerActor
   * @return a [[akka.actor.IO.SocketHandle]] to uniquely identify the created socket
   */
  @deprecated("use the new implementation in package akka.io instead", "2.2")
  def connect(host: String, port: Int)(implicit owner: ActorRef): IO.SocketHandle =
    connect(new InetSocketAddress(host, port))(owner)

}

//FIXME add docs
object IOManager extends ExtensionId[IOManager] with ExtensionIdProvider {
  override def lookup: IOManager.type = this
  override def createExtension(system: ExtendedActorSystem): IOManager = new IOManager(system)

  @SerialVersionUID(1L)
  case class Settings(readBufferSize: Int, selectInterval: Int, defaultBacklog: Int) {
    require(readBufferSize <= Int.MaxValue && readBufferSize > 0)
    require(selectInterval > 0)
  }
}

/**
 * An [[akka.actor.Actor]] that performs IO using a Java NIO Selector.
 *
 * Use [[akka.actor.IOManager]] to retrieve an instance of this Actor.
 */
final class IOManagerActor(val settings: Settings) extends Actor with ActorLogging {
  import SelectionKey.{ OP_READ, OP_WRITE, OP_ACCEPT, OP_CONNECT }
  import settings.{ defaultBacklog, selectInterval, readBufferSize }

  private type ReadChannel = ReadableByteChannel with SelectableChannel
  private type WriteChannel = WritableByteChannel with SelectableChannel

  private val selector: Selector = Selector open ()

  private val channels = mutable.Map.empty[IO.Handle, SelectableChannel]

  private val accepted = mutable.Map.empty[IO.ServerHandle, mutable.Queue[SelectableChannel]]

  private val writes = mutable.Map.empty[IO.WriteHandle, WriteBuffer]

  /** Channels that should close after writes are complete */
  private val closing = mutable.Set.empty[IO.Handle]

  /** Buffer used for all reads */
  private val buffer = ByteBuffer.allocate(readBufferSize)

  /** a counter that is incremented each time a message is retrieved */
  private var lastSelect = 0

  /** true while the selector is open and channels.nonEmpty */
  private var running = false

  /** is there already a Select message in flight? */
  private var selectSent = false

  /**
   * select blocks for 1ms when false and is completely nonblocking when true.
   * Automatically changes due to activity. This reduces object allocations
   * when there are no pending events.
   */
  private var fastSelect = false

  /** unique message that is sent to ourself to initiate the next select */
  private case object Select

  /** This method should be called after receiving any message */
  private def run() {
    if (!running) {
      running = true
      if (!selectSent) {
        selectSent = true
        self ! Select
      }
    }
    lastSelect += 1
    if (lastSelect >= selectInterval)
      running = select()
  }

  /**
   * @return true if we should be running and false if not
   */
  private def select(): Boolean = try {
    if (selector.isOpen) {
      // TODO: Make select behaviour configurable.
      // Blocking 1ms reduces allocations during idle times, non blocking gives better performance.
      if (fastSelect) selector.selectNow else selector.select(1)
      val keys = selector.selectedKeys.iterator
      fastSelect = keys.hasNext
      while (keys.hasNext) {
        val key = keys.next()
        keys.remove()
        if (key.isValid) process(key)
      }
      if (channels.isEmpty) false else running
    } else {
      false
    }
  } finally {
    lastSelect = 0
  }

  private def forwardFailure(f: ⇒ Unit): Unit = try f catch { case NonFatal(e) ⇒ sender ! Status.Failure(e) }

  private def setSocketOptions(socket: java.net.Socket, options: immutable.Seq[IO.SocketOption]) {
    options foreach {
      case IO.KeepAlive(on)           ⇒ forwardFailure(socket.setKeepAlive(on))
      case IO.OOBInline(on)           ⇒ forwardFailure(socket.setOOBInline(on))
      case IO.ReceiveBufferSize(size) ⇒ forwardFailure(socket.setReceiveBufferSize(size))
      case IO.ReuseAddress(on)        ⇒ forwardFailure(socket.setReuseAddress(on))
      case IO.SendBufferSize(size)    ⇒ forwardFailure(socket.setSendBufferSize(size))
      case IO.SoLinger(linger)        ⇒ forwardFailure(socket.setSoLinger(linger.isDefined, math.max(0, linger.getOrElse(socket.getSoLinger))))
      case IO.SoTimeout(timeout)      ⇒ forwardFailure(socket.setSoTimeout(timeout.toMillis.toInt))
      case IO.TcpNoDelay(on)          ⇒ forwardFailure(socket.setTcpNoDelay(on))
      case IO.TrafficClass(tc)        ⇒ forwardFailure(socket.setTrafficClass(tc))
      case IO.PerformancePreferences(connTime, latency, bandwidth) ⇒
        forwardFailure(socket.setPerformancePreferences(connTime, latency, bandwidth))
    }
  }

  def receive = {
    case Select ⇒
      running = select()
      if (running) self ! Select
      selectSent = running

    case IO.Listen(server, address, options) ⇒
      val channel = ServerSocketChannel open ()
      try {
        channel configureBlocking false
        var backlog = defaultBacklog
        val sock = channel.socket
        options foreach {
          case IO.ReceiveBufferSize(size) ⇒ forwardFailure(sock.setReceiveBufferSize(size))
          case IO.ReuseAddress(on)        ⇒ forwardFailure(sock.setReuseAddress(on))
          case IO.PerformancePreferences(connTime, latency, bandwidth) ⇒
            forwardFailure(sock.setPerformancePreferences(connTime, latency, bandwidth))
          case IO.Backlog(number) ⇒ backlog = number
        }
        sock bind (address, backlog)
        channels update (server, channel)
        channel register (selector, OP_ACCEPT, server)
        server.owner ! IO.Listening(server, sock.getLocalSocketAddress())
      } catch {
        case NonFatal(e) ⇒ {
          channel close ()
          sender ! Status.Failure(e)
        }
      }
      run()

    case IO.Connect(socket, address, options) ⇒
      val channel = SocketChannel open ()
      try {
        channel configureBlocking false
        channel connect address
        setSocketOptions(channel.socket, options)
        channels update (socket, channel)
        channel register (selector, OP_CONNECT | OP_READ, socket)
      } catch {
        case NonFatal(e) ⇒ {
          channel close ()
          sender ! Status.Failure(e)
        }
      }
      run()

    case IO.Accept(socket, server, options) ⇒
      val queue = accepted(server)
      val channel = queue.dequeue()

      channel match { case socketChannel: SocketChannel ⇒ setSocketOptions(socketChannel.socket, options) }

      channels update (socket, channel)
      channel register (selector, OP_READ, socket)
      run()

    case IO.Write(handle, data) ⇒
      if (channels contains handle) {
        val queue = writes get handle getOrElse {
          val q = new WriteBuffer(readBufferSize)
          writes update (handle, q)
          q
        }
        if (queue.isEmpty) addOps(handle, OP_WRITE)
        queue enqueue data
        if (queue.length >= readBufferSize) write(handle, channels(handle).asInstanceOf[WriteChannel])
      }
      run()

    case IO.Close(handle: IO.WriteHandle) ⇒
      //If we still have pending writes, add to set of closing handles
      if (writes get handle exists (_.isEmpty == false)) closing += handle
      else cleanup(handle, IO.EOF)
      run()

    case IO.Close(handle) ⇒
      cleanup(handle, IO.EOF)
      run()
  }

  override def postStop {
    channels.keys foreach (handle ⇒ cleanup(handle, IO.EOF))
    selector.close
  }

  private def process(key: SelectionKey) {
    val handle = key.attachment.asInstanceOf[IO.Handle]
    try {
      if (key.isConnectable) key.channel match { case channel: SocketChannel ⇒ connect(handle.asSocket, channel) }
      if (key.isAcceptable) key.channel match { case channel: ServerSocketChannel ⇒ accept(handle.asServer, channel) }
      if (key.isReadable) key.channel match { case channel: ReadChannel ⇒ read(handle.asReadable, channel) }
      if (key.isWritable) key.channel match { case channel: WriteChannel ⇒ try write(handle.asWritable, channel) catch { case e: IOException ⇒ } } // ignore, let it fail on read to ensure nothing left in read buffer.
    } catch {
      case e @ (_: ClassCastException | _: CancelledKeyException | _: IOException | _: ActorInitializationException) ⇒
        cleanup(handle, IO.Error(e)) //Scala patmat is broken
    }
  }

  private def cleanup(handle: IO.Handle, cause: IO.Input) {
    closing -= handle
    handle match {
      case server: IO.ServerHandle  ⇒ accepted -= server
      case writable: IO.WriteHandle ⇒ writes -= writable
    }
    channels.get(handle) foreach {
      channel ⇒
        try channel.close finally {
          channels -= handle
          if (!handle.owner.isTerminated) handle.owner ! IO.Closed(handle, cause)
        }
    }
  }

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
      socket.owner ! IO.Connected(socket, channel.socket.getRemoteSocketAddress())
    } else {
      cleanup(socket, IO.Error(new IllegalStateException("Channel for socket handle [%s] didn't finish connect" format socket)))
    }
  }

  @tailrec
  private def accept(server: IO.ServerHandle, channel: ServerSocketChannel) {
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
  private def read(handle: IO.ReadHandle, channel: ReadChannel) {
    buffer.clear
    val readLen = channel read buffer
    if (readLen == -1) {
      cleanup(handle, IO.EOF)
    } else if (readLen > 0) {
      buffer.flip
      handle.owner ! IO.Read(handle, ByteString(buffer))
      if (readLen == buffer.capacity) read(handle, channel)
    }
  }

  private def write(handle: IO.WriteHandle, channel: WriteChannel) {
    val queue = writes(handle)
    queue write channel
    if (queue.isEmpty) {
      if (closing(handle)) cleanup(handle, IO.EOF)
      else removeOps(handle, OP_WRITE)
    }
  }
}

//FIXME is this public API?
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

  def length: Int = _length

  def isEmpty: Boolean = _length == 0

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
