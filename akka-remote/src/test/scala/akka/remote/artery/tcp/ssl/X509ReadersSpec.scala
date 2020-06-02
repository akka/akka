/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp.ssl

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 *
 */
class X509ReadersSpec extends AnyWordSpec with Matchers {
  import TlsResourcesSpec._

  "X509Readers" must {
    "read a certificate's name from the CN" in {
      val island = loadCert("/ssl/island.example.com.crt")
      X509Readers.getAllSubjectNames(island) mustBe (Set("island.example.com"))
    }

    "read both the CN and the subject alternative names" in {
      val serverCert = loadCert("/domain.crt")
      X509Readers.getAllSubjectNames(serverCert) mustBe (Set("akka-remote", "localhost"))
    }
  }

}
