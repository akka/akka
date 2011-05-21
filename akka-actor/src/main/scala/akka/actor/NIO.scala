/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.actor

import java.nio.channels.{ SelectableChannel, Selector, SelectionKey }
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

object NIO {
  private[akka] case class Accept(key: SelectionKey)
  private[akka] case class Read(key: SelectionKey)
  private[akka] case class Write(key: SelectionKey)
}

class NIOHandler {
  private[akka] val activeKeys = java.util.Collections.synchronizedSet(new java.util.HashSet[SelectionKey]())

  private val pendingInterestOps = new AtomicReference(Map.empty[SelectableChannel, Int])
  private val pendingRegister = new AtomicReference(Map.empty[SelectableChannel, (Int, ActorRef)])

  private val selector: Selector = Selector.open()

  private val select = new Runnable {
    def run(): Unit = {
      while (true) {
        selector.select
        selector.selectedKeys.removeAll(activeKeys)
        val selectedKeys = selector.selectedKeys.iterator
        while (selectedKeys.hasNext) {
          val key = selectedKeys.next
          selectedKeys.remove
          activeKeys.add(key)
          if (key.isValid) {
            val owner = key.attachment.asInstanceOf[ActorRef]
            if (key.isAcceptable) owner ! NIO.Accept(key)
            else if (key.isReadable) owner ! NIO.Read(key)
            else if (key.isWritable) owner ! NIO.Write(key)
          }
        }
        pendingRegister.getAndSet(Map.empty[SelectableChannel, (Int, ActorRef)]) foreach {
          case (channel, (interestOps, actor)) ⇒ channel.register(selector, interestOps, actor)
        }
        pendingInterestOps.getAndSet(Map.empty[SelectableChannel, Int]) foreach {
          case (channel, interestOps) ⇒ channel.keyFor(selector).interestOps(interestOps)
        }
      }
    }
  }

  private val thread = new Thread(select)
  thread.start

  @scala.annotation.tailrec
  final def register(channel: SelectableChannel, interestOps: Int)(implicit owner: Some[ActorRef]): Unit = {
    val orig = pendingRegister.get
    if (pendingRegister.compareAndSet(orig, orig + (channel -> (interestOps, owner.get))))
      selector.wakeup
    else
      register(channel, interestOps)
  }

  final def unregister(channel: SelectableChannel): Unit =
    channel.keyFor(selector).cancel

  @scala.annotation.tailrec
  final def interestOps(channel: SelectableChannel, interestOps: Int): Unit = {
    val orig = pendingInterestOps.get
    if (pendingInterestOps.compareAndSet(orig, orig + (channel -> interestOps)))
      selector.wakeup
    else
      this.interestOps(channel, interestOps)
  }
}

trait NIO {
  this: Actor ⇒

  import NIO._

  def nioHandler: NIOHandler

  val originalReceive = receive

  become {
    case Accept(key) ⇒
      accept(key.channel())
      nioHandler.activeKeys.remove(key)
    case Read(key) ⇒
      read(key.channel())
      nioHandler.activeKeys.remove(key)
    case Write(key) ⇒
      write(key.channel())
      nioHandler.activeKeys.remove(key)
    case other if originalReceive.isDefinedAt(other) ⇒ originalReceive(other)
  }

  def accept(channel: SelectableChannel): Unit

  def read(channel: SelectableChannel): Unit

  def write(channel: SelectableChannel): Unit
}
