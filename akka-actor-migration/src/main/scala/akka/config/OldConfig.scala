/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.config
import akka.actor.GlobalActorSystem
import com.typesafe.config.Config

/**
 * Migration replacement for `object akka.config.Config`.
 */
@deprecated("use ActorSystem.settings.config instead", "2.0")
object OldConfig {

  val config = new OldConfiguration(GlobalActorSystem.settings.config)

}

/**
 * Migration adapter for `akka.config.Configuration`
 */
@deprecated("use ActorSystem.settings.config (com.typesafe.config.Config) instead", "2.0")
class OldConfiguration(config: Config) {

  import scala.collection.JavaConverters._

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def contains(key: String): Boolean = config.hasPath(key)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def keys: Iterable[String] = config.root.keySet.asScala

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getAny(key: String): Option[Any] = {
    try {
      Option(config.getAnyRef(key))
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getAny(key: String, defaultValue: Any): Any = getAny(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getSeqAny(key: String): Seq[Any] = {
    try {
      config.getAnyRefList(key).asScala
    } catch {
      case _ ⇒ Seq.empty[Any]
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getString(key: String): Option[String] =
    try {
      Option(config.getString(key))
    } catch {
      case _ ⇒ None
    }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getString(key: String, defaultValue: String): String = getString(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getList(key: String): Seq[String] = {
    try {
      config.getStringList(key).asScala
    } catch {
      case _ ⇒ Seq.empty[String]
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getInt(key: String): Option[Int] = {
    try {
      Option(config.getInt(key))
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getInt(key: String, defaultValue: Int): Int = getInt(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getLong(key: String): Option[Long] = {
    try {
      Option(config.getLong(key))
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getLong(key: String, defaultValue: Long): Long = getLong(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getFloat(key: String): Option[Float] = {
    try {
      Option(config.getDouble(key).toFloat)
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getFloat(key: String, defaultValue: Float): Float = getFloat(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getDouble(key: String): Option[Double] = {
    try {
      Option(config.getDouble(key))
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getDouble(key: String, defaultValue: Double): Double = getDouble(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getBoolean(key: String): Option[Boolean] = {
    try {
      Option(config.getBoolean(key))
    } catch {
      case _ ⇒ None
    }
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getBoolean(key: String, defaultValue: Boolean): Boolean = getBoolean(key).getOrElse(defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getBool(key: String): Option[Boolean] = getBoolean(key)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getBool(key: String, defaultValue: Boolean): Boolean = getBoolean(key, defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def apply(key: String): String = getString(key) match {
    case None    ⇒ throw new ConfigurationException("undefined config: " + key)
    case Some(v) ⇒ v
  }

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def apply(key: String, defaultValue: String) = getString(key, defaultValue)
  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def apply(key: String, defaultValue: Int) = getInt(key, defaultValue)
  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def apply(key: String, defaultValue: Long) = getLong(key, defaultValue)
  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def apply(key: String, defaultValue: Boolean) = getBool(key, defaultValue)

  @deprecated("use new com.typesafe.config.Config API instead", "2.0")
  def getSection(name: String): Option[OldConfiguration] = {
    try {
      Option(new OldConfiguration(config.getConfig(name)))
    } catch {
      case _ ⇒ None
    }
  }
}