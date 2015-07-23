/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.util

import java.lang.reflect.{ InvocationTargetException, Method }
import javax.net.ssl.SSLParameters

import scala.util.control.NonFatal

/**
 * INTERNAL API
 *
 * Enables accessing SslParameters even if compiled against Java 6.
 */
private[http] object Java6Compat {
  /**
   * Returns true if setting the algorithm was successful.
   */
  def setEndpointIdentificationAlgorithm(parameters: SSLParameters, algorithm: String): Boolean =
    setEndpointIdentificationAlgorithmFunction(parameters, algorithm)

  private[this] val setEndpointIdentificationAlgorithmFunction: (SSLParameters, String) ⇒ Boolean = {
    def unsupported: (SSLParameters, String) ⇒ Boolean = (_, _) ⇒ false

    def callReflectively(m: Method) =
      (params: SSLParameters, algorithm: String) ⇒
        try {
          m.invoke(params, algorithm)
          true
        } catch {
          case ite: InvocationTargetException ⇒ throw ite.getTargetException
        }

    try {
      val m = classOf[SSLParameters].getMethod("setEndpointIdentificationAlgorithm", classOf[java.lang.String])

      val candidate =
        if (m.getReturnType == Void.TYPE && m.getParameterTypes.toSeq == Seq(classOf[java.lang.String])) {
          if (!m.isAccessible) m.setAccessible(true)
          callReflectively(m)
        } else unsupported

      // probe so that we can be sure a reflective problem only turns up once
      // here during construction
      candidate(new SSLParameters(), "https")
      candidate
    } catch {
      case NonFatal(_) ⇒ unsupported
    }
  }
}
