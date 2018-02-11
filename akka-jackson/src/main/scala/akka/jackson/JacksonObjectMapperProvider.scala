/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.jackson

import scala.util.Failure
import scala.util.Success

import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.annotation.InternalApi
import akka.event.Logging
import akka.event.LoggingAdapter
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.typesafe.config.Config

/**
 * INTERNAL API
 */
@InternalApi private[akka] object JacksonObjectMapperProvider {

  /**
   * Creates Jackson `ObjectMapper` with sensible defaults and modules configured
   * in `akka.jackson.jackson-modules`.
   */
  def create(system: ExtendedActorSystem, jsonFactory: Option[JsonFactory]): ObjectMapper =
    create(
      system.settings.config,
      system.dynamicAccess,
      Some(Logging.getLogger(system, JacksonObjectMapperProvider.getClass)),
      jsonFactory)

  /**
   * Creates Jackson `ObjectMapper` with sensible defaults and modules configured
   * in `akka.jackson.jackson-modules`.
   */
  def create(
    config:        Config,
    dynamicAccess: DynamicAccess,
    log:           Option[LoggingAdapter],
    jsonFactory:   Option[JsonFactory]): ObjectMapper = {
    import scala.collection.JavaConverters._

    val mapper = new ObjectMapper(jsonFactory.orNull)

    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

    val configuredModules = config.getStringList(
      "akka.jackson.jackson-modules"
    ).asScala
    val modules =
      if (configuredModules.contains("*"))
        ObjectMapper.findModules(dynamicAccess.classLoader).asScala
      else configuredModules.flatMap { fqcn ⇒
        dynamicAccess.createInstanceFor[Module](fqcn, Nil) match {
          case Success(m) ⇒ Some(m)
          case Failure(e) ⇒
            log.foreach(_.error(e, s"Could not load configured Jackson module [$fqcn], " +
              "please verify classpath dependencies or amend the configuration " +
              "[akka.jackson-modules]. Continuing without this module."))
            None
        }
      }

    modules.foreach { module ⇒
      if (module.isInstanceOf[ParameterNamesModule])
        // ParameterNamesModule needs a special case for the constructor to ensure that single-parameter
        // constructors are handled the same way as constructors with multiple parameters.
        // See https://github.com/FasterXML/jackson-module-parameter-names#delegating-creator
        mapper.registerModule(new ParameterNamesModule(JsonCreator.Mode.PROPERTIES))
      else mapper.registerModule(module)
      log.foreach(_.debug("Registered Jackson module [{}]", module.getClass.getName))
    }

    mapper
  }
}
