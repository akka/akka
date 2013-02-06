/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import java.net.DatagramSocket
import akka.io.Inet.SocketOption
import com.typesafe.config.Config
import akka.actor.{ Props, ActorSystemImpl }

object Udp {

  object SO extends Inet.SoForwarders {

    /**
     * [[akka.io.Inet.SocketOption]] to set the SO_BROADCAST option
     *
     * For more information see [[java.net.DatagramSocket#setBroadcast]]
     */
    case class Broadcast(on: Boolean) extends SocketOption {
      override def beforeDatagramBind(s: DatagramSocket): Unit = s.setBroadcast(on)
    }

  }

  private[io] class UdpSettings(_config: Config) extends SelectionHandlerSettings(_config) {
    import _config._

    val NrOfSelectors = getInt("nr-of-selectors")
    val DirectBufferSize = getIntBytes("direct-buffer-size")
    val MaxDirectBufferPoolSize = getInt("max-direct-buffer-pool-size")
    val BatchReceiveLimit = getInt("batch-receive-limit")

    val ManagementDispatcher = getString("management-dispatcher")

    // FIXME: Use new requiring
    require(NrOfSelectors > 0, "nr-of-selectors must be > 0")

    override val MaxChannelsPerSelector = if (MaxChannels == -1) -1 else math.max(MaxChannels / NrOfSelectors, 1)

    private[this] def getIntBytes(path: String): Int = {
      val size = getBytes(path)
      require(size < Int.MaxValue, s"$path must be < 2 GiB")
      size.toInt
    }
  }

}
