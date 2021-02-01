/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import akka.stream.TLSClientAuth
import akka.stream.TLSProtocol.NegotiateNewSession
import javax.net.ssl.{ SSLContext, SSLEngine, SSLParameters }
import org.scalatest.funspec.AnyFunSpec

class TLSUtilsSpec extends AnyFunSpec {

  describe("To have Client authentication defined NEED") {
    val sslEngine: SSLEngine = SSLContext.getDefault.createSSLEngine()
    val sslParams: SSLParameters = new SSLParameters()
    val negotiableSession: NegotiateNewSession =
      NegotiateNewSession(None, None, Some(TLSClientAuth.Need), Some(sslParams))

    it(
      "SSLParameters does not by default require client authentication when it is not passed to the constructor of HttpConnectionContext. In that case its default value is used by akka-http.") {
      assert(!sslParams.getNeedClientAuth)
    }

    it("NegotiableSession has ClientAuth defined Need") {
      assert(negotiableSession.clientAuth.contains(TLSClientAuth.Need))
    }

    it(
      "SSLEngine must have ClientAuth defined Need after calling akka.stream.impl.io.TlsUtils.applySessionParameters with sslEngine and negotiableSession") {
      TlsUtils.applySessionParameters(sslEngine, negotiableSession)
      assert(sslEngine.getSSLParameters.getNeedClientAuth)
    }
  }
}
