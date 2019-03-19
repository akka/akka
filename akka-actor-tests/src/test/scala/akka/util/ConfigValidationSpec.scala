/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import akka.actor.DefaultSupervisorStrategy
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class ConfigValidationSpec extends AkkaSpec {
  import ConfigFactory.parseString
  import ConfigValidation._

  "ConfigValidation" must {
    val empty = ConfigFactory.empty
    val config = system.settings.config
    val emptyConfigValue = parseString("""akka.empty = "" """)

    "fail fast in validation if an expected plugin is missing" in {
      intercept[ConfigValidationError](config.plugin("akka.missing.fqcn"))
      intercept[ConfigValidationError](config.plugin(null))
      intercept[ConfigValidationError](config.plugin(""))
      intercept[ConfigValidationError](empty.plugin("akka.missing.fqcn"))
    }

    "return valid plugin if config is valid" in {
      val plugin = config.plugin("akka.actor.guardian-supervisor-strategy")
      plugin shouldBe classOf[DefaultSupervisorStrategy]
    }

    "fail fast with 'valueOrThrow' if invalid" in {
      intercept[ConfigValidationError](config.valueOrThrow[String]("akka.missing")(stringConfigVal))
      intercept[ConfigValidationError](config.valueOrThrow[Boolean]("akka.missing")(booleanConfigVal))
      intercept[ConfigValidationError](empty.valueOrThrow[String]("akka.empty")(stringConfigVal))
      intercept[ConfigValidationError](empty.valueOrThrow[Boolean]("akka.empty")(booleanConfigVal))
    }

    "convert value with 'valueOrThrow' if valid" in {
      config.valueOrThrow[String]("akka.loglevel")(stringConfigVal) shouldBe "WARNING"
      config.valueOrThrow[Boolean]("akka.daemonic")(booleanConfigVal) shouldBe false
    }

    "convert value with 'string' and 'stringOrElse' if valid" in {
      config.string("akka.loglevel") shouldBe "WARNING"
      config.stringOrElse("akka.not.exists", "fallback") shouldBe "fallback"
    }

    "fail fast on 'string' if invalid" in {
      intercept[ConfigValidationError](config.string("akka.not.exists"))
    }

    "convert value with 'boolean' and 'booleanOrElse' if valid" in {
      config.boolean("akka.daemonic") shouldBe false
      empty.booleanOrElse("akka.fu", true) shouldBe true
    }

    "fail fast 'boolean' if invalid" in {
      intercept[ConfigValidationError](config.boolean("akka.not.exists"))
    }

    "convert 'as' expected type" in {
      config.as[Boolean]("akka.daemonic")(booleanConfigVal) shouldBe false
      config.as[String]("akka.loglevel")(stringConfigVal) shouldBe "WARNING"
    }

    "fail fast if config for 'as' is invalid" in {
      val wrongType = parseString("""akka.wrong.type = 0s """)

      intercept[ConfigValidationError](wrongType.as[Boolean]("akka.wrong")(booleanConfigVal))
      intercept[ConfigValidationError](emptyConfigValue.as[String]("akka.empty")(stringConfigVal))
      intercept[ConfigValidationError](config.as[String](null)(stringConfigVal))
    }

    "convert 'valueOr' to None if invalid" in {
      empty.valueOr[Boolean]("akka.expected")(booleanConfigVal) shouldBe None
    }

    "convert 'valueOr' to Some value if valid" in {
      config.valueOr[Boolean]("akka.daemonic")(booleanConfigVal) shouldBe Some(false)
    }

    "convert 'valueOrElse' to default if invalid" in {
      empty.valueOrElse[String]("akka.expected", "default")(stringConfigVal) shouldBe "default"
      emptyConfigValue.valueOrElse[String]("akka.empty", "default")(stringConfigVal) shouldBe "default"
      emptyConfigValue.valueOrElse[String](null, "default")(stringConfigVal) shouldBe "default"
    }

    "convert 'valueOrElse' to the value if valid" in {
      parseString("akka.expected = on").valueOrElse[Boolean]("akka.expected", false)(booleanConfigVal) shouldBe true
    }

    "be valid if path and config are valid values" in {
      ConfigValidation.isValid("akka.daemonic", config) shouldBe true
    }

    "be invalid if config is invalid" in {
      ConfigValidation.isValid("akka.not.exists", config) shouldBe false
    }

    "be invalid if path is invalid" in {
      ConfigValidation.isValid(null, empty) shouldBe false
      ConfigValidation.isValid("", empty) shouldBe false
    }

    "fail fast with strings where not typesafe" in {
      config.getString("akka.daemonic") shouldBe "off" // we want to fail but it converts it
      intercept[ConfigValidationError](config.string("akka.daemonic"))
    }
  }
}
