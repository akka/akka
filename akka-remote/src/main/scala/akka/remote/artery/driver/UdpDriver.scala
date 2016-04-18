/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import java.net.{ InetSocketAddress, StandardSocketOptions }
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util
import java.util.concurrent.atomic.AtomicBoolean

import akka.dispatch.AbstractNodeQueue
import akka.io.DirectByteBufferPool
import akka.stream.stage.AsyncCallback
import org.agrona.concurrent.{ BackoffIdleStrategy, OneToOneConcurrentArrayQueue }

import scala.util.control.Breaks._

/**
 * INTERNAL API
 */
private[remote] object UdpDriver {

  val Debug = true
  val SendQueueSize = 128
  val RcvQueueSize = 128

  sealed trait Command
  case object Shutdown extends Command
  final case class Register(
    registrationCallback: AsyncCallback[Registration],
    wakeupCallback: AsyncCallback[Unit],
    remoteAddress: InetSocketAddress) extends Command
  final case class Deregister(registration: Registration) extends Command

  final class CommandQueue extends AbstractNodeQueue[Command]

  val ignoreWakeup = new AsyncCallback[Unit] {
    override def invoke(t: Unit): Unit = if (Debug) println("Ignored wakeup")
  }

  final class Registration(val owner: UdpDriver, val remoteAddress: InetSocketAddress) {
    val rcvBuffer = new FrameBuffer(owner.bufferPool)
    val sendQueue = new OneToOneConcurrentArrayQueue[Frame](SendQueueSize)
    val rcvQueue = new OneToOneConcurrentArrayQueue[Frame](RcvQueueSize)
    val readWeakupNeeded = new AtomicBoolean(false)

    private var wakeupCallback: AsyncCallback[Unit] = ignoreWakeup

    def setWakeupCallback(cb: AsyncCallback[Unit]): Unit = {
      // ignoreWakeup is only the initial behavior
      if (cb ne ignoreWakeup) wakeupCallback = cb
    }

    def deregister(): Unit = owner.command(Deregister(this))

    def enqueueForWrite(frame: Frame): Unit = {
      sendQueue.offer(frame)
    }

    def enqueueRead(frame: Frame): Unit = {
      // Drop if overloaded
      if (!rcvQueue.offer(frame)) rcvBuffer.release(frame)
    }

    def readWakeUp(): Unit = {
      readWeakupNeeded.set(false)
      wakeupCallback.invoke(())
    }
  }

  val MaxIterationsUntilProcessCommand = 32

}

/**
 * INTERNAL API
 */
private[remote] final class UdpDriver(listenAddress: InetSocketAddress) extends Runnable {
  import UdpDriver._

  val bufferPool = new DirectByteBufferPool(FrameBuffer.BufferSize, 128) // TODO: Proper maximum buffer count

  private[this] val queue = new CommandQueue
  private[this] var channel: DatagramChannel = _
  private[this] val backoff: BackoffIdleStrategy = new BackoffIdleStrategy(
    128, // maxSpins
    128, // maxYields
    128, // minParkPeriodNs
    65536 // maxParkPeriodNs
    )

  private[this] var wasIoAction = false
  private[this] val registrations = new util.ArrayList[Registration]()
  private[this] val addressToRegistration = new util.HashMap[InetSocketAddress, Registration]()
  private[this] val dispatchBuffer = ByteBuffer.allocateDirect(FrameBuffer.FrameSize)

  def start(): Unit = {
    val thread = new Thread(this)
    thread.setDaemon(true)
    thread.start()
  }
  def stop(): Unit = {
    command(Shutdown)
  }

  def command(cmd: Command): Unit = {
    queue.add(cmd)
  }

  override def run(): Unit = {
    try {
      preStart()
      mainLoop()
    } finally {
      // FIXME: add error reporting
      postStop()
    }
  }

  private def preStart(): Unit = {
    channel = DatagramChannel.open()
    channel.configureBlocking(false)
    //channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
    channel.bind(new InetSocketAddress(listenAddress.getPort))
  }

  private def postStop(): Unit = {
    channel.close()
    // TODO: Deallocate buffer pool, have no public API for that
  }

  private def mainLoop(): Unit = {
    breakable {
      while (true) {
        val cmd = queue.poll()
        if (cmd ne null) processCommand(cmd)
        var ioIterations = MaxIterationsUntilProcessCommand
        do {
          if (Debug) println("iterations " + ioIterations)
          ioIterations -= 1
          wasIoAction = false
          receive()
          scanAndSend()
        } while (wasIoAction && ioIterations > 0)
        //backoff.idle()
        if (Debug) Thread.sleep(500)
      }
    }
  }

  private def processCommand(cmd: Command): Unit = {
    if (Debug) println("process command " + cmd)
    backoff.reset()
    cmd match {
      case Shutdown ⇒ break()
      case Register(callback, wakeupCallback, remoteAddress) ⇒
        callback.invoke(registrationForAddress(remoteAddress, wakeupCallback))

      case Deregister(registration) ⇒
        registrations.remove(registrations.indexOf(registration))
        addressToRegistration.remove(registration.remoteAddress)
    }
  }

  private def registrationForAddress(remoteAddress: InetSocketAddress, cb: AsyncCallback[Unit]): Registration = {
    val existingRegistration = addressToRegistration.get(remoteAddress)

    if (existingRegistration eq null) {
      val registration = new Registration(this, remoteAddress)
      registrations.add(registration)
      addressToRegistration.put(remoteAddress, registration)
      registration.setWakeupCallback(cb)
      registration
    } else {
      existingRegistration.setWakeupCallback(cb)
      existingRegistration
    }
  }

  private def receive(): Unit = {
    if (Debug) println("receiving")
    val address = channel.receive(dispatchBuffer).asInstanceOf[InetSocketAddress]
    if (address ne null) {
      println("received " + address)
      backoff.reset()
      wasIoAction = true
      val registration = registrationForAddress(address, ignoreWakeup)
      val frame = registration.rcvBuffer.aquire()
      // Drop if we have no space in the FrameBuffer
      if (frame ne null) {
        dispatchBuffer.flip()
        frame.buffer.clear()
        frame.buffer.put(dispatchBuffer)
        dispatchBuffer.clear()
        registration.rcvQueue.offer(frame)
      }
    }
  }

  private def scanAndSend(): Unit = {
    if (Debug) println("scanAndSend")
    val registrationsItr = registrations.iterator()
    while (registrationsItr.hasNext) {
      val registration = registrationsItr.next()
      if (registration.readWeakupNeeded.get() && !registration.rcvQueue.isEmpty) {
        if (Debug) println("wake up registration")
        registration.readWakeUp()
      }
      val queue = registration.sendQueue
      var frame = queue.peek()
      while (frame ne null) {
        if (Debug) println("Attempt to write")
        frame.buffer.flip()
        if (channel.send(frame.buffer, registration.remoteAddress) > 0) {
          if (Debug) println("written")
          queue.poll()
          backoff.reset()
          wasIoAction = true
          frame = queue.peek()
        } else {
          // FIXME: Uncomsumed frame must be un-flipped
          frame = null // Exit write loop
        }
      }
    }
  }
}
