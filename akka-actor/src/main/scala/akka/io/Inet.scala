/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.nio.channels.{ DatagramChannel, SocketChannel, ServerSocketChannel }

object Inet {

  /**
   * SocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a channel).
   */
  trait SocketOption {

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(ds: DatagramChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(ss: ServerSocketChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    def beforeBind(s: SocketChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: DatagramChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: ServerSocketChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(c: SocketChannel): Unit = ()
  }

  /**
   * DatagramChannel creation behavior.
   */
  class DatagramChannelCreator extends SocketOption {

    /**
     * Open and return new DatagramChannel.
     *
     * [[scala.throws]] is needed because [[DatagramChannel.open]] method
     * can throw an exception.
     */
    @throws(classOf[Exception])
    def create(): DatagramChannel = DatagramChannel.open()
  }

  object DatagramChannelCreator {
    val default = new DatagramChannelCreator()
    def apply() = default
  }

  object SO {

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket.setReceiveBufferSize]]
     */
    final case class ReceiveBufferSize(size: Int) extends SocketOption {
      require(size > 0, "ReceiveBufferSize must be > 0")
      override def beforeBind(c: ServerSocketChannel): Unit = c.socket.setReceiveBufferSize(size)
      override def beforeBind(c: DatagramChannel): Unit = c.socket.setReceiveBufferSize(size)
      override def beforeBind(c: SocketChannel): Unit = c.socket.setReceiveBufferSize(size)
    }

    // server socket options

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    final case class ReuseAddress(on: Boolean) extends SocketOption {
      override def beforeBind(c: ServerSocketChannel): Unit = c.socket.setReuseAddress(on)
      override def beforeBind(c: DatagramChannel): Unit = c.socket.setReuseAddress(on)
      override def beforeBind(c: SocketChannel): Unit = c.socket.setReuseAddress(on)
    }

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    final case class SendBufferSize(size: Int) extends SocketOption {
      require(size > 0, "SendBufferSize must be > 0")
      override def afterConnect(c: DatagramChannel): Unit = c.socket.setSendBufferSize(size)
      override def afterConnect(c: SocketChannel): Unit = c.socket.setSendBufferSize(size)
    }

    /**
     * [[akka.io.Inet.SocketOption]] to set the traffic class or
     * type-of-service octet in the IP header for packets sent from this
     * socket.
     *
     * For more information see [[java.net.Socket.setTrafficClass]]
     */
    final case class TrafficClass(tc: Int) extends SocketOption {
      require(0 <= tc && tc <= 255, "TrafficClass needs to be in the interval [0, 255]")
      override def afterConnect(c: DatagramChannel): Unit = c.socket.setTrafficClass(tc)
      override def afterConnect(c: SocketChannel): Unit = c.socket.setTrafficClass(tc)
    }

  }

  trait SoForwarders {
    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket.setReceiveBufferSize]]
     */
    val ReceiveBufferSize = SO.ReceiveBufferSize

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    val ReuseAddress = SO.ReuseAddress

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    val SendBufferSize = SO.SendBufferSize

    /**
     * [[akka.io.Inet.SocketOption]] to set the traffic class or
     * type-of-service octet in the IP header for packets sent from this
     * socket.
     *
     * For more information see [[java.net.Socket.setTrafficClass]]
     */
    val TrafficClass = SO.TrafficClass
  }

  trait SoJavaFactories {
    import SO._
    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket.setReceiveBufferSize]]
     */
    def receiveBufferSize(size: Int) = ReceiveBufferSize(size)

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    def reuseAddress(on: Boolean) = ReuseAddress(on)

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    def sendBufferSize(size: Int) = SendBufferSize(size)

    /**
     * [[akka.io.Inet.SocketOption]] to set the traffic class or
     * type-of-service octet in the IP header for packets sent from this
     * socket.
     *
     * For more information see [[java.net.Socket.setTrafficClass]]
     */
    def trafficClass(tc: Int) = TrafficClass(tc)
  }

  /**
   * Java API: AbstractSocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a channel).
   */
  abstract class AbstractSocketOption extends SocketOption {

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(ds: DatagramChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(ss: ServerSocketChannel): Unit = ()

    /**
     * Action to be taken for this option before bind() is called
     */
    override def beforeBind(s: SocketChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: DatagramChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: ServerSocketChannel): Unit = ()

    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    override def afterConnect(c: SocketChannel): Unit = ()
  }
}
