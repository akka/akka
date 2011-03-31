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
    "contain all configuration properties for akka-stm that are used in code with their correct defaults" in {
      import Config.config._

      getBool("akka.stm.blocking-allowed") must equal(Some(false))
      getBool("akka.stm.fair") must equal(Some(true))
      getBool("akka.stm.interruptible") must equal(Some(false))
      getInt("akka.stm.max-retries") must equal(Some(1000))
      getString("akka.stm.propagation") must equal(Some("requires"))
      getBool("akka.stm.quick-release") must equal(Some(true))
      getBool("akka.stm.speculative") must equal(Some(true))
      getLong("akka.stm.timeout") must equal(Some(5))
      getString("akka.stm.trace-level") must equal(Some("none"))
      getBool("akka.stm.write-skew") must equal(Some(true))
    }
  }
}
