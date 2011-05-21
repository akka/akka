/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import akka.config.Supervision.Permanent

import java.nio.channels.{ SelectableChannel, ServerSocketChannel, Selector, SelectionKey, CancelledKeyException }
import java.net.InetSocketAddress
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections.synchronizedSet
import java.util.HashSet

import scala.annotation.tailrec

object NIO {

  object Ops {
    def apply(op: Op): Int = op.value
    def apply(op1: Op, op2: Op, ops: Op*): Int = op1.value | op2.value | apply(ops)
    def apply(ops: Traversable[Op]): Int = (0 /: ops)(_ | _.value)
  }

  sealed trait Op { def value: Int }
  object Connect extends Op { val value = SelectionKey.OP_CONNECT }
  object Accept extends Op { val value = SelectionKey.OP_ACCEPT }
  object Read extends Op { val value = SelectionKey.OP_READ }
  object Write extends Op { val value = SelectionKey.OP_WRITE }

  sealed trait SelectedOp { def channel: SelectableChannel; def value: Int }
  case class Connect(channel: SelectableChannel) extends SelectedOp { def value = Connect.value }
  case class Accept(channel: SelectableChannel) extends SelectedOp { def value = Accept.value }
  case class Read(channel: SelectableChannel) extends SelectedOp { def value = Read.value }
  case class Write(channel: SelectableChannel) extends SelectedOp { def value = Write.value }

}

trait NIO {
  this: Actor ⇒

  def nioHandler: NIOHandler

  val originalReceive = receive

  become {
    case selectedOp: NIO.SelectedOp ⇒
      try {
        receiveIO(selectedOp)
      } catch {
        case e: IOException ⇒ // Any reason why this is bad?
          nioHandler unregister selectedOp.channel
          selectedOp.channel.close
          throw e
      }
      finally {
        nioHandler received selectedOp
      }
    case other if originalReceive.isDefinedAt(other) ⇒ originalReceive(other)
  }

  def receiveIO: PartialFunction[NIO.SelectedOp, Unit]
}

trait NIOSimpleServer extends NIO {
  this: Actor ⇒

  def host: String

  def port: Int

  def createWorker: Actor with NIO

  self.lifeCycle = Permanent

  var nioHandler: NIOHandler = _

  override def preStart = {
    nioHandler = new NIOHandler
    val inetAddress = new InetSocketAddress(host: String, port: Int)
    val server = ServerSocketChannel open ()
    server configureBlocking false
    server.socket bind inetAddress
    nioHandler register (server, NIO.Accept)
  }

  override def preRestart(reason: scala.Throwable) = {
    nioHandler stop ()
  }

  def receiveIO = {
    case NIO.Accept(server: ServerSocketChannel) ⇒ accept(server)
  }

  @tailrec
  final def accept(server: ServerSocketChannel): Unit = {
    val client = server accept ()
    if (client ne null) {
      client configureBlocking false
      nioHandler.register(client, NIO.Read)(Some(self startLink (Actor.actorOf(createWorker))))
      accept(server)
    }
  }
}

object NIOHandler {
  sealed trait ChangeRequest { def channel: SelectableChannel }
  case class Register(channel: SelectableChannel, interestOps: Int, owner: ActorRef) extends ChangeRequest
  case class InterestOps(channel: SelectableChannel, interestOps: Int) extends ChangeRequest
}

class NIOHandler {
  import NIOHandler._

  private val _activeOps = new AtomicReference(Map.empty[SelectableChannel, Int].withDefaultValue(0))

  private val _changeRequests = new AtomicReference(List.empty[ChangeRequest])

  private val selector: Selector = Selector open ()

  private val select = new Runnable {
    def run(): Unit = {
      while (selector.isOpen) {
        val keys = selector.selectedKeys.iterator
        while (keys.hasNext) {
          val key = keys next ()
          keys remove ()
          if (key.isValid) { notifyOwner(key) }
        }
        _changeRequests.getAndSet(Nil).reverse foreach {
          case Register(channel, ops, owner) ⇒ channel register (selector, ops, owner)
          case InterestOps(channel, ops) ⇒
            try {
              channel keyFor selector interestOps ops
            } catch {
              case e: CancelledKeyException ⇒ resetActiveOps(channel)
            }
        }
        selector select ()
      }
    }
  }

  private val thread = new Thread(select)
  thread.start

  private def notifyOwner(key: SelectionKey): Unit = {
    val channel = key.channel

    @tailrec
    def updateActiveOps(): Int = {
      val activeOps = _activeOps.get
      val ops = activeOps(channel)
      val diffOps = (key.readyOps | ops) - (key.readyOps & ops)
      val newOps = diffOps - (diffOps & ops)
      if (newOps > 0) {
        if (_activeOps.compareAndSet(activeOps, activeOps.updated(channel, ops | newOps))) newOps
        else updateActiveOps()
      } else 0
    }

    try {
      val ops = updateActiveOps()
      if (ops > 0) {
        val owner = key.attachment.asInstanceOf[ActorRef]
        if ((ops & NIO.Connect.value) > 0) owner ! NIO.Connect(channel)
        if ((ops & NIO.Accept.value) > 0) owner ! NIO.Accept(channel)
        if ((ops & NIO.Read.value) > 0) owner ! NIO.Read(channel)
        if ((ops & NIO.Write.value) > 0) owner ! NIO.Write(channel)
      }
    } catch {
      case e: CancelledKeyException        ⇒ resetActiveOps(channel)
      case e: ActorInitializationException ⇒ resetActiveOps(channel)
    }
  }

  private def resetActiveOps(channel: SelectableChannel): Unit = {
    val activeOps = _activeOps.get
    if (!_activeOps.compareAndSet(activeOps, activeOps - channel)) resetActiveOps(channel)
  }

  final def register(channel: SelectableChannel)(implicit owner: Some[ActorRef]): Unit =
    register(channel, 0)(owner)

  final def register(channel: SelectableChannel, op: NIO.Op)(implicit owner: Some[ActorRef]): Unit =
    register(channel, NIO.Ops(op))(owner)

  final def register(channel: SelectableChannel, op1: NIO.Op, op2: NIO.Op, ops: NIO.Op*)(implicit owner: Some[ActorRef]): Unit =
    register(channel, NIO.Ops(op1, op2, ops: _*))(owner)

  final def register(channel: SelectableChannel, ops: Traversable[NIO.Op])(implicit owner: Some[ActorRef]): Unit =
    register(channel, NIO.Ops(ops))(owner)

  final def register(channel: SelectableChannel, ops: Int)(implicit owner: Some[ActorRef]): Unit =
    addChangeRequest(Register(channel, ops, owner.get))

  final def unregister(channel: SelectableChannel): Unit =
    channel keyFor selector cancel ()

  final def interestOps(channel: SelectableChannel): Unit =
    interestOps(channel, 0)

  final def interestOps(channel: SelectableChannel, op: NIO.Op): Unit =
    interestOps(channel, NIO.Ops(op))

  final def interestOps(channel: SelectableChannel, op1: NIO.Op, op2: NIO.Op, ops: NIO.Op*): Unit =
    interestOps(channel, NIO.Ops(op1, op2, ops: _*))

  final def interestOps(channel: SelectableChannel, ops: Traversable[NIO.Op]): Unit =
    interestOps(channel, NIO.Ops(ops))

  final def interestOps(channel: SelectableChannel, ops: Int): Unit =
    addChangeRequest(InterestOps(channel, ops))

  @tailrec
  private def addChangeRequest(req: ChangeRequest): Unit = {
    val changeRequests = _changeRequests.get
    if (_changeRequests compareAndSet (changeRequests, req :: changeRequests))
      selector wakeup ()
    else
      addChangeRequest(req)
  }

  @tailrec
  private[akka] final def received(op: NIO.SelectedOp): Unit = {
    val activeOps = _activeOps.get
    val ops = activeOps(op.channel)
    if ((ops & op.value) > 0) {
      val newOps = ops - op.value
      if (newOps == 0) {
        if (!_activeOps.compareAndSet(activeOps, activeOps - op.channel)) received(op)
      } else {
        if (!_activeOps.compareAndSet(activeOps, activeOps.updated(op.channel, newOps))) received(op)
      }
    }
  }

  final def stop(): Unit = selector close ()

}
