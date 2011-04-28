/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends WordSpec with MustMatchers {

  "The default configuration file (i.e. akka-reference.conf)" should {
    "contain all configuration properties for akka-http that are used in code with their correct defaults" in {
      import Config.config._

      getString("akka.http.authenticator") must equal(Some("N/A"))
      getBool("akka.http.connection-close") must equal(Some(true))
      getString("akka.http.expired-header-name") must equal(Some("Async-Timeout"))
      getList("akka.http.filters") must equal(List("akka.security.AkkaSecurityFilterFactory"))
      getList("akka.http.resource-packages") must equal(Nil)
      getString("akka.http.hostname") must equal(Some("localhost"))
      getString("akka.http.expired-header-value") must equal(Some("expired"))
      getString("akka.http.kerberos.servicePrincipal") must equal(Some("N/A"))
      getString("akka.http.kerberos.keyTabLocation") must equal(Some("N/A"))
      getString("akka.http.kerberos.kerberosDebug") must equal(Some("N/A"))
      getString("akka.http.kerberos.realm") must equal(Some(""))
      getInt("akka.http.port") must equal(Some(9998))
      getBool("akka.http.root-actor-builtin") must equal(Some(true))
      getString("akka.http.root-actor-id") must equal(Some("_httproot"))
      getLong("akka.http.timeout") must equal(Some(1000))
    }
  }
}
