/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.{ DatagramSocket, Socket, ServerSocket, InetAddress, NetworkInterface }

object Inet {

  /**
   * SocketOption is a package of data (from the user) and associated
   * behavior (how to apply that to a socket).
   */
  trait SocketOption {

    def beforeDatagramBind(ds: DatagramSocket): Unit = ()

    def beforeServerSocketBind(ss: ServerSocket): Unit = ()

    /**
     * Action to be taken for this option before calling connect()
     */
    def beforeConnect(s: Socket): Unit = ()
    /**
     * Action to be taken for this option after connect returned (i.e. on
     * the slave socket for servers).
     */
    def afterConnect(s: Socket): Unit = ()
  }

  object SO {

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_RCVBUF option
     *
     * For more information see [[java.net.Socket.setReceiveBufferSize]]
     */
    final case class ReceiveBufferSize(size: Int) extends SocketOption {
      require(size > 0, "ReceiveBufferSize must be > 0")
      override def beforeServerSocketBind(s: ServerSocket): Unit = s.setReceiveBufferSize(size)
      override def beforeDatagramBind(s: DatagramSocket): Unit = s.setReceiveBufferSize(size)
      override def beforeConnect(s: Socket): Unit = s.setReceiveBufferSize(size)
    }

    // server socket options

    /**
     * [[akka.io.Inet.SocketOption]] to enable or disable SO_REUSEADDR
     *
     * For more information see [[java.net.Socket.setReuseAddress]]
     */
    final case class ReuseAddress(on: Boolean) extends SocketOption {
      override def beforeServerSocketBind(s: ServerSocket): Unit = s.setReuseAddress(on)
      override def beforeDatagramBind(s: DatagramSocket): Unit = s.setReuseAddress(on)
      override def beforeConnect(s: Socket): Unit = s.setReuseAddress(on)
    }

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_SNDBUF option.
     *
     * For more information see [[java.net.Socket.setSendBufferSize]]
     */
    final case class SendBufferSize(size: Int) extends SocketOption {
      require(size > 0, "SendBufferSize must be > 0")
      override def afterConnect(s: Socket): Unit = s.setSendBufferSize(size)
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
      override def afterConnect(s: Socket): Unit = s.setTrafficClass(tc)
    }

    /**
     * [[akka.io.Inet.SocketOption]] to join a multicast group to begin
     * receiving all datagrams sent to the group.
     *
     * For more information see [[java.nio.channels.MulticastChannel.join]]
     */
    final case class JoinGroup(group: InetAddress, interf: NetworkInterface) extends SocketOption {
      override def beforeDatagramBind(ds: DatagramSocket): Unit = ds.getChannel.join(group, interf)
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

}
