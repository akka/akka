/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import scala.util.Try
import scala.util.control.NonFatal

import akka.annotation.InternalApi
import com.typesafe.config.Config

@InternalApi
private[akka] object ConfigValidation {

  /** Allow usage outside validation. */
  def isValid(path: String, config: Config): Boolean =
    path != null && path.nonEmpty && config.hasPath(path)

  def message(key: String): String =
    s"Configuration [$key] was expected but is not configured."

  trait ConfigVal[A] {
    def value(path: String, config: Config): A
  }

  implicit def stringConfigVal: ConfigVal[String] = new ConfigVal[String] {
    def value(path: String, config: Config): String = config.getString(path)
  }
  implicit def booleanConfigVal: ConfigVal[Boolean] = new ConfigVal[Boolean] {
    def value(path: String, config: Config): Boolean = config.getBoolean(path)
  }

  final implicit class ConfigValidationOps(val config: Config) extends AnyVal {

    /** Fail fast on config error. */
    def as[A](path: String)(implicit reader: ConfigVal[A]): A =
      if (isValid(path, config)) {
        Try(reader.value(path, config) match {
          case null =>
            throw ConfigValidationError(s"Value must not be null for path $path")
          case v: String if v == "off" || v == "on" =>
            throw ConfigValidationError(s"Expected String $path but found Boolean $v")
          case v: String if v.isEmpty =>
            throw ConfigValidationError(s"Invalid value $v for path $path")
          case v => v
        }).recover {
          case e: ConfigValidationError => throw e
          case NonFatal(e)              => throw ConfigValidationError(e.getMessage)
        }.get
      } else throw ConfigValidationError(message(path))

    def valueOr[A](path: String)(implicit reader: ConfigVal[A]): Option[A] =
      Try(config.as[A](path)(reader)).toOption

    def valueOrElse[A](path: String, default: A)(implicit reader: ConfigVal[A]): A =
      valueOr[A](path)(reader).getOrElse(default)

    def valueOrThrow[A](path: String)(implicit reader: ConfigVal[A]): A =
      valueOr[A](path).getOrElse(throw ConfigValidationError(message(path)))

    // ------- Fail fast ---------
    // Just a few implementations because the idea may be thrown out
    // Fill in others if we decide to keep.

    def string(path: String): String =
      valueOrThrow[String](path)(stringConfigVal)

    def boolean(path: String): Boolean =
      valueOrThrow[Boolean](path)(booleanConfigVal)

    def stringOrElse(path: String, default: String): String =
      valueOrElse[String](path, default)(stringConfigVal)

    def booleanOrElse(path: String, default: Boolean): Boolean =
      valueOrElse[Boolean](path, default)(booleanConfigVal)

    def plugin(path: String): Class[_] =
      valueOr[String](path)(stringConfigVal).map(Class.forName).getOrElse(throw ConfigValidationError(message(path)))
  }

  final case class ConfigValidationError(message: String) extends IllegalArgumentException

}
