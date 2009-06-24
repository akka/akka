/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.nio

import java.io.PrintWriter
import java.net.{Socket, InetAddress, InetSocketAddress}

import java.util.concurrent.Executors
import java.util.{HashSet, Date}
import java.nio.channels.{Selector, ServerSocketChannel, SelectionKey}
import java.nio.channels.spi.SelectorProvider
import kernel.actor.Invocation
import kernel.reactor.{MessageQueue, MessageDemultiplexer, MessageHandle, MessageDispatcherBase}

class ProxyServer extends MessageDispatcherBase {
  val port = 9999
  val host = InetAddress.getLocalHost

  // Selector for incoming time requests
  val acceptSelector = SelectorProvider.provider.openSelector

  // Create a new server socket and set to non blocking mode
  val ssc = ServerSocketChannel.open
  ssc.configureBlocking(true)

  // Bind the server socket to the local host and port
  val address = new InetSocketAddress(host, port)
  ssc.socket.bind(address)

  // Register accepts on the server socket with the selector. This
  // step tells the selector that the socket wants to be put on the
  // ready list when accept operations occur, so allowing multiplexed
  // non-blocking I/O to take place.
  val acceptKey = ssc.register(acceptSelector, SelectionKey.OP_ACCEPT)

  // FIXME: make configurable using configgy + JMX
  // FIXME: create one executor per invocation to dispatch(..), grab config settings for specific actor (set in registerHandler)
  private val threadPoolSize: Int = 100
  private val handlerExecutor = Executors.newCachedThreadPool()

  def start = if (!active) {
    active = true
    selectorThread = new Thread {
      override def run = {
        while (active) {
          try {
            guard.synchronized { /* empty */ } // prevents risk for deadlock as described in [http://developers.sun.com/learning/javaoneonline/2006/coreplatform/TS-1315.pdf]

            val keysAdded = acceptSelector.select
            val readyKeys = acceptSelector.selectedKeys
            val iter = readyKeys.iterator
            while (iter.hasNext) {
              val key = iter.next.asInstanceOf[SelectionKey]
              iter.remove
/*
             if (key.isValid && key.isReadable) {
                eventHandler.onReadableEvent(key.channel)
              }
              if (key.isValid && key.isWritable) {
                key.interestOps(SelectionKey.OP_READ) // reset to read only
                eventHandler.onWriteableEvent(key.channel)
              }
*/
              val channel = key.channel.asInstanceOf[ServerSocketChannel]
              val socket = channel.accept.socket
              socket.setKeepAlive(true)


              val in = socket.getInputStream
              val out = new PrintWriter(socket.getOutputStream, true)
              out.println(new Date)
              out.close

              /*              handlerExecutor.execute(new Runnable {
                              override def run = {
                                try {
                                  val result = handle.message.asInstanceOf[Invocation].joinpoint.proceed
                                  handle.future.completeWithResult(result)
                                } catch {
                                  case e: Exception => handle.future.completeWithException(e)
                                }
                              }
                            })
              */
            }
          } finally {
          }
        }
      }
    }
    selectorThread.start
  }

  override protected def doShutdown = handlerExecutor.shutdownNow
}
