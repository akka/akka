/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

import scala.collection.immutable
import scala.compat.java8.OptionConverters._
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.actor.DynamicAccess
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.setup.Setup
import akka.annotation.InternalStableApi
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.util.unused
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.typesafe.config.Config
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonGenerator

object JacksonObjectMapperProvider extends ExtensionId[JacksonObjectMapperProvider] with ExtensionIdProvider {
  override def get(system: ActorSystem): JacksonObjectMapperProvider = super.get(system)

  override def lookup = JacksonObjectMapperProvider

  override def createExtension(system: ExtendedActorSystem): JacksonObjectMapperProvider =
    new JacksonObjectMapperProvider(system)

  /**
   * The configuration for a given `bindingName`.
   */
  def configForBinding(bindingName: String, systemConfig: Config): Config = {
    val basePath = "akka.serialization.jackson"
    val baseConf = systemConfig.getConfig("akka.serialization.jackson")
    if (systemConfig.hasPath(s"$basePath.$bindingName"))
      systemConfig.getConfig(s"$basePath.$bindingName").withFallback(baseConf)
    else
      baseConf
  }

  /**
   * INTERNAL API: Use [[JacksonObjectMapperProvider#create]]
   *
   * This is needed by one test in Lagom where the ObjectMapper is created without starting and ActorSystem.
   */
  @InternalStableApi
  def createObjectMapper(
      bindingName: String,
      jsonFactory: Option[JsonFactory],
      objectMapperFactory: JacksonObjectMapperFactory,
      config: Config,
      dynamicAccess: DynamicAccess,
      log: Option[LoggingAdapter]) = {

    import akka.util.ccompat.JavaConverters._

    val mapper = objectMapperFactory.newObjectMapper(bindingName, jsonFactory)

    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)

    val configuredSerializationFeatures =
      features(config, "serialization-features").map {
        case (enumName, value) => SerializationFeature.valueOf(enumName) -> value
      }
    val serializationFeatures =
      objectMapperFactory.overrideConfiguredSerializationFeatures(bindingName, configuredSerializationFeatures)
    serializationFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredDeserializationFeatures =
      features(config, "deserialization-features").map {
        case (enumName, value) => DeserializationFeature.valueOf(enumName) -> value
      }
    val deserializationFeatures =
      objectMapperFactory.overrideConfiguredDeserializationFeatures(bindingName, configuredDeserializationFeatures)
    deserializationFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredMapperFeatures = features(config, "mapper-features").map {
      case (enumName, value) => MapperFeature.valueOf(enumName) -> value
    }
    val mapperFeatures = objectMapperFactory.overrideConfiguredMapperFeatures(bindingName, configuredMapperFeatures)
    mapperFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredJsonParserFeatures = features(config, "json-parser-features").map {
      case (enumName, value) => JsonParser.Feature.valueOf(enumName) -> value
    }
    val jsonParserFeatures =
      objectMapperFactory.overrideConfiguredJsonParserFeatures(bindingName, configuredJsonParserFeatures)
    jsonParserFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredJsonGeneratorFeatures = features(config, "json-generator-features").map {
      case (enumName, value) => JsonGenerator.Feature.valueOf(enumName) -> value
    }
    val jsonGeneratorFeatures =
      objectMapperFactory.overrideConfiguredJsonGeneratorFeatures(bindingName, configuredJsonGeneratorFeatures)
    jsonGeneratorFeatures.foreach {
      case (feature, value) => mapper.configure(feature, value)
    }

    val configuredModules = config.getStringList("jackson-modules").asScala
    val modules1 =
      configuredModules.flatMap { fqcn =>
        if (isModuleEnabled(fqcn, dynamicAccess)) {
          dynamicAccess.createInstanceFor[Module](fqcn, Nil) match {
            case Success(m) => Some(m)
            case Failure(e) =>
              log.foreach(
                _.error(
                  e,
                  s"Could not load configured Jackson module [$fqcn], " +
                  "please verify classpath dependencies or amend the configuration " +
                  "[akka.serialization.jackson-modules]. Continuing without this module."))
              None
          }
        } else
          None
      }

    val modules2 = modules1.map { module =>
      if (module.isInstanceOf[ParameterNamesModule])
        // ParameterNamesModule needs a special case for the constructor to ensure that single-parameter
        // constructors are handled the same way as constructors with multiple parameters.
        // See https://github.com/FasterXML/jackson-module-parameter-names#delegating-creator
        new ParameterNamesModule(JsonCreator.Mode.PROPERTIES)
      else module
    }.toList

    val modules3 = objectMapperFactory.overrideConfiguredModules(bindingName, modules2)

    modules3.foreach { module =>
      mapper.registerModule(module)
      log.foreach(_.debug("Registered Jackson module [{}]", module.getClass.getName))
    }

    mapper
  }

  private def isModuleEnabled(fqcn: String, dynamicAccess: DynamicAccess): Boolean =
    fqcn match {
      case "akka.serialization.jackson.AkkaTypedJacksonModule" =>
        // akka-actor-typed dependency is "provided" and may not be included
        dynamicAccess.classIsOnClasspath("akka.actor.typed.ActorRef")
      case "akka.serialization.jackson.AkkaStreamJacksonModule" =>
        // akka-stream dependency is "provided" and may not be included
        dynamicAccess.classIsOnClasspath("akka.stream.Graph")
      case _ => true
    }

  private def features(config: Config, section: String): immutable.Seq[(String, Boolean)] = {
    import akka.util.ccompat.JavaConverters._
    val cfg = config.getConfig(section)
    cfg.root.keySet().asScala.map(key => key -> cfg.getBoolean(key)).toList
  }
}

/**
 * Registry of shared `ObjectMapper` instances, each with it's unique `bindingName`.
 */
final class JacksonObjectMapperProvider(system: ExtendedActorSystem) extends Extension {
  private val objectMappers = new ConcurrentHashMap[String, ObjectMapper]

  /**
   * Scala API: Returns an existing Jackson `ObjectMapper` that was created previously with this method, or
   * creates a new instance.
   *
   * The `ObjectMapper` is created with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * The returned `ObjectMapper` must not be modified, because it may already be in use and such
   * modifications are not thread-safe.
   *
   * @param bindingName name of this `ObjectMapper`
   * @param jsonFactory optional `JsonFactory` such as `CBORFactory`, for plain JSON `None` (defaults)
   *                    can be used
   */
  def getOrCreate(bindingName: String, jsonFactory: Option[JsonFactory]): ObjectMapper = {
    objectMappers.computeIfAbsent(bindingName, _ => create(bindingName, jsonFactory))
  }

  /**
   * Java API: Returns an existing Jackson `ObjectMapper` that was created previously with this method, or
   * creates a new instance.
   *
   * The `ObjectMapper` is created with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * The returned `ObjectMapper` must not be modified, because it may already be in use and such
   * modifications are not thread-safe.
   *
   * @param bindingName name of this `ObjectMapper`
   * @param jsonFactory optional `JsonFactory` such as `CBORFactory`, for plain JSON `None` (defaults)
   *                    can be used
   */
  def getOrCreate(bindingName: String, jsonFactory: Optional[JsonFactory]): ObjectMapper =
    getOrCreate(bindingName, jsonFactory.asScala)

  /**
   * Scala API: Creates a new instance of a Jackson `ObjectMapper` with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * @param bindingName name of this `ObjectMapper`
   * @param jsonFactory optional `JsonFactory` such as `CBORFactory`, for plain JSON `None` (defaults)
   *                    can be used
   * @see [[JacksonObjectMapperProvider#getOrCreate]]
   */
  def create(bindingName: String, jsonFactory: Option[JsonFactory]): ObjectMapper = {
    val log = Logging.getLogger(system, JacksonObjectMapperProvider.getClass)
    val dynamicAccess = system.dynamicAccess

    val config = JacksonObjectMapperProvider.configForBinding(bindingName, system.settings.config)

    val factory = system.settings.setup.get[JacksonObjectMapperProviderSetup] match {
      case Some(setup) => setup.factory
      case None        => new JacksonObjectMapperFactory // default
    }

    JacksonObjectMapperProvider.createObjectMapper(bindingName, jsonFactory, factory, config, dynamicAccess, Some(log))
  }

  /**
   * Java API: Creates a new instance of a Jackson `ObjectMapper` with sensible defaults and modules configured
   * in `akka.serialization.jackson.jackson-modules`. It's using [[JacksonObjectMapperProviderSetup]]
   * if the `ActorSystem` is started with such [[akka.actor.setup.ActorSystemSetup]].
   *
   * @param bindingName name of this `ObjectMapper`
   * @param jsonFactory optional `JsonFactory` such as `CBORFactory`, for plain JSON `None` (defaults)
   *                    can be used
   * @see [[JacksonObjectMapperProvider#getOrCreate]]
   */
  def create(bindingName: String, jsonFactory: Optional[JsonFactory]): ObjectMapper =
    create(bindingName, jsonFactory.asScala)

}

object JacksonObjectMapperProviderSetup {

  /**
   * Scala API: factory for defining a `JacksonObjectMapperProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def apply(factory: JacksonObjectMapperFactory): JacksonObjectMapperProviderSetup =
    new JacksonObjectMapperProviderSetup(factory)

  /**
   * Java API: factory for defining a `JacksonObjectMapperProvider` that is passed in when ActorSystem
   * is created rather than creating one from configured class name.
   */
  def create(factory: JacksonObjectMapperFactory): JacksonObjectMapperProviderSetup =
    apply(factory)

}

/**
 * Setup for defining a `JacksonObjectMapperProvider` that can be passed in when ActorSystem
 * is created rather than creating one from configured class name. Create a subclass of
 * [[JacksonObjectMapperFactory]] and override the methods to amend the defaults.
 */
final class JacksonObjectMapperProviderSetup(val factory: JacksonObjectMapperFactory) extends Setup

/**
 * Used with [[JacksonObjectMapperProviderSetup]] for defining a `JacksonObjectMapperProvider` that can be
 * passed in when ActorSystem is created rather than creating one from configured class name.
 * Create a subclass and override the methods to amend the defaults.
 */
class JacksonObjectMapperFactory {

  /**
   * Override this method to create a new custom instance of `ObjectMapper` for the given `serializerIdentifier`.
   *
   * @param bindingName name of this `ObjectMapper`
   * @param jsonFactory optional `JsonFactory` such as `CBORFactory`, for plain JSON `None` (defaults)
   *                    can be used
   */
  def newObjectMapper(@unused bindingName: String, jsonFactory: Option[JsonFactory]): ObjectMapper =
    new ObjectMapper(jsonFactory.orNull)

  /**
   * After construction of the `ObjectMapper` the configured serialization features are applied to
   * the mapper. These features can be amended programatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * When implementing a `JacksonObjectMapperFactory` with Java the `immutable.Seq` can be
   * created with [[akka.japi.Util.immutableSeq]].
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredFeatures the list of `SerializationFeature` that were configured in
   *                           `akka.serialization.jackson.serialization-features`
   */
  def overrideConfiguredSerializationFeatures(
      @unused bindingName: String,
      configuredFeatures: immutable.Seq[(SerializationFeature, Boolean)])
      : immutable.Seq[(SerializationFeature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured deserialization features are applied to
   * the mapper. These features can be amended programatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * When implementing a `JacksonObjectMapperFactory` with Java the `immutable.Seq` can be
   * created with [[akka.japi.Util.immutableSeq]].
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredFeatures the list of `DeserializationFeature` that were configured in
   *                           `akka.serialization.jackson.deserialization-features`
   */
  def overrideConfiguredDeserializationFeatures(
      @unused bindingName: String,
      configuredFeatures: immutable.Seq[(DeserializationFeature, Boolean)])
      : immutable.Seq[(DeserializationFeature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured modules are added to
   * the mapper. These modules can be amended programatically by overriding this method and
   * return the modules that are to be applied to the `ObjectMapper`.
   *
   * When implementing a `JacksonObjectMapperFactory` with Java the `immutable.Seq` can be
   * created with [[akka.japi.Util.immutableSeq]].
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredModules the list of `Modules` that were configured in
   *                           `akka.serialization.jackson.deserialization-features`
   */
  def overrideConfiguredModules(
      @unused bindingName: String,
      configuredModules: immutable.Seq[Module]): immutable.Seq[Module] =
    configuredModules

  /**
   * After construction of the `ObjectMapper` the configured mapper features are applied to
   * the mapper. These features can be amended programmatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredFeatures the list of `MapperFeatures` that were configured in `akka.serialization.jackson.mapper-features`
   */
  def overrideConfiguredMapperFeatures(
      @unused bindingName: String,
      configuredFeatures: immutable.Seq[(MapperFeature, Boolean)]): immutable.Seq[(MapperFeature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured JSON parser features are applied to
   * the mapper. These features can be amended programmatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredFeatures the list of `JsonParser.Feature` that were configured in `akka.serialization.jackson.json-parser-features`
   */
  def overrideConfiguredJsonParserFeatures(
      @unused bindingName: String,
      configuredFeatures: immutable.Seq[(JsonParser.Feature, Boolean)]): immutable.Seq[(JsonParser.Feature, Boolean)] =
    configuredFeatures

  /**
   * After construction of the `ObjectMapper` the configured JSON generator features are applied to
   * the mapper. These features can be amended programmatically by overriding this method and
   * return the features that are to be applied to the `ObjectMapper`.
   *
   * @param bindingName bindingName name of this `ObjectMapper`
   * @param configuredFeatures the list of `JsonGenerator.Feature` that were configured in `akka.serialization.jackson.json-generator-features`
   */
  def overrideConfiguredJsonGeneratorFeatures(
      @unused bindingName: String,
      configuredFeatures: immutable.Seq[(JsonGenerator.Feature, Boolean)])
      : immutable.Seq[(JsonGenerator.Feature, Boolean)] =
    configuredFeatures
}
