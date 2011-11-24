/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.serialization

import akka.actor.ActorSystem
import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ActorSystemImpl
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.config.ConfigurationException

object SerializationExtensionKey extends ExtensionKey[SerializationExtension]

object SerializationExtension {
  def apply(system: ActorSystem): SerializationExtension = {
    if (!system.hasExtension(SerializationExtensionKey)) {
      system.registerExtension(new SerializationExtension)
    }
    system.extension(SerializationExtensionKey)
  }

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-serialization-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-serialization").withFallback(cfg).withFallback(referenceConfig).resolve()

    import scala.collection.JavaConverters._
    import config._

    val Serializers: Map[String, String] = {
      toStringMap(getConfig("akka.actor.serializers"))
    }

    val SerializationBindings: Map[String, Seq[String]] = {
      val configPath = "akka.actor.serialization-bindings"
      hasPath(configPath) match {
        case false ⇒ Map()
        case true ⇒
          val serializationBindings: Map[String, Seq[String]] = getConfig(configPath).toObject.unwrapped.asScala.toMap.map {
            case (k: String, v: java.util.Collection[_]) ⇒ (k -> v.asScala.toSeq.asInstanceOf[Seq[String]])
            case invalid                                 ⇒ throw new ConfigurationException("Invalid serialization-bindings [%s]".format(invalid))
          }
          serializationBindings

      }
    }

    private def toStringMap(mapConfig: Config): Map[String, String] = {
      mapConfig.toObject.unwrapped.asScala.toMap.map { entry ⇒
        (entry._1 -> entry._2.toString)
      }
    }

  }
}

class SerializationExtension extends Extension[SerializationExtension] {
  import SerializationExtension._
  @volatile
  private var _settings: Settings = _
  @volatile
  private var _serialization: Serialization = _
  def serialization = _serialization

  def key = SerializationExtensionKey

  def init(system: ActorSystemImpl) {
    _settings = new Settings(system.applicationConfig)
    _serialization = new Serialization(system)
  }

  def settings: Settings = _settings

}