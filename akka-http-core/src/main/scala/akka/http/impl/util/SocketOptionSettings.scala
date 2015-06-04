/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import akka.io.{ Tcp, Inet }

import scala.collection.immutable

import akka.io.Inet.SocketOption
import com.typesafe.config.Config

private[http] object SocketOptionSettings {
  def fromSubConfig(config: Config): immutable.Traversable[SocketOption] = {
    def so[T](setting: String)(f: (Config, String) ⇒ T)(cons: T ⇒ SocketOption): List[SocketOption] =
      config.getString(setting) match {
        case "undefined" ⇒ Nil
        case x           ⇒ cons(f(config, setting)) :: Nil
      }

    so("so-receive-buffer-size")(_ getIntBytes _)(Inet.SO.ReceiveBufferSize) :::
      so("so-send-buffer-size")(_ getIntBytes _)(Inet.SO.SendBufferSize) :::
      so("so-reuse-address")(_ getBoolean _)(Inet.SO.ReuseAddress) :::
      so("so-traffic-class")(_ getInt _)(Inet.SO.TrafficClass) :::
      so("tcp-keep-alive")(_ getBoolean _)(Tcp.SO.KeepAlive) :::
      so("tcp-oob-inline")(_ getBoolean _)(Tcp.SO.OOBInline) :::
      so("tcp-no-delay")(_ getBoolean _)(Tcp.SO.TcpNoDelay)
  }
}
