/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stm.test

import org.junit.runner.RunWith
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import akka.testkit.AkkaSpec

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.parseResource(classOf[ConfigSpec], "/akka-stm-reference.conf", ConfigParseOptions.defaults)) {

  "The default configuration file (i.e. akka-stm-reference.conf)" should {
    "contain all configuration properties for akka-stm that are used in code with their correct defaults" in {
      val config = system.settings.config
      import config._

      // TODO are these config values used anywhere?

      getBoolean("akka.stm.blocking-allowed") must equal(false)
      getBoolean("akka.stm.fair") must equal(true)
      getBoolean("akka.stm.interruptible") must equal(false)
      getInt("akka.stm.max-retries") must equal(1000)
      getString("akka.stm.propagation") must equal("requires")
      getBoolean("akka.stm.quick-release") must equal(true)
      getBoolean("akka.stm.speculative") must equal(true)
      getMilliseconds("akka.stm.timeout") must equal(5 * 1000)
      getString("akka.stm.trace-level") must equal("none")
      getBoolean("akka.stm.write-skew") must equal(true)
    }
  }
}
