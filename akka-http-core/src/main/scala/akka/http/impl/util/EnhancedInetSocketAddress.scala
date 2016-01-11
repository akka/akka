/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.lang.reflect.{ InvocationTargetException, Method }
import java.net.InetSocketAddress

import scala.util.control.NonFatal

/**
 * Provides getHostString support for Java 6.
 *
 * TODO: can be removed once support for Java 6 is dropped.
 *
 * Internal API
 */
private[http] class EnhancedInetSocketAddress(val address: InetSocketAddress) extends AnyVal {
  /**
   * Retrieve the original host string that was given (IP or DNS name) if the current JDK has
   * a `getHostString` method with the right signature that can be made accessible.
   *
   * This avoids a reverse DNS query from calling getHostName() if the original host string is an IP address.
   * If the reflective call doesn't work it falls back to getHostName.
   */
  def getHostStringJava6Compatible: String = EnhancedInetSocketAddress.getHostStringFunction(address)
}

/**
 * Internal API
 */
private[http] object EnhancedInetSocketAddress {
  private[http] val getHostStringFunction: InetSocketAddress ⇒ String = {
    def fallbackToGetHostName = (_: InetSocketAddress).getHostName
    def callReflectively(m: Method) =
      (address: InetSocketAddress) ⇒
        try m.invoke(address).asInstanceOf[String]
        catch {
          case ite: InvocationTargetException ⇒ throw ite.getTargetException
        }

    try {
      val m = classOf[InetSocketAddress].getDeclaredMethod("getHostString")

      val candidate =
        if (m.getReturnType == classOf[String] && m.getParameterTypes.isEmpty) {
          if (!m.isAccessible) m.setAccessible(true)
          callReflectively(m)
        } else fallbackToGetHostName

      // probe so that we can be sure a reflective problem only turns up once
      // here during construction
      candidate(new InetSocketAddress("127.0.0.1", 80))
      candidate
    } catch {
      case NonFatal(_) ⇒ fallbackToGetHostName
    }
  }
}