/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.kernel

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfigSpec extends AkkaSpec(ConfigFactory.defaultReference) {

  "The default configuration file (i.e. reference.conf)" must {
    "contain correct defaults for akka-kernel" in {

      val config = system.settings.config

      config.getString("akka.kernel.system.name") must be === "default"
      config.getList("akka.kernel.boot").asScala.toList must be === Nil
    }
  }
}
