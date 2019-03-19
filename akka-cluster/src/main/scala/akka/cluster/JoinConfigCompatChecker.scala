/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import java.util

import akka.actor.ExtendedActorSystem
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.util.ccompat._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValue }

import scala.collection.JavaConverters._
import scala.collection.{ immutable => im }

abstract class JoinConfigCompatChecker {

  /** The configuration keys that are required for this checker */
  def requiredKeys: im.Seq[String]

  /**
   * Runs the Config check.
   *
   * Implementers are free to define what makes Config entry compatible or not.
   * We do provide some pre-build checks tough: [[JoinConfigCompatChecker.exists()]] and [[JoinConfigCompatChecker.fullMatch()]]
   *
   * @param toCheck - the Config instance to be checked
   * @param actualConfig - the Config instance containing the actual values
   * @return a [[ConfigValidation]]. Can be [[Valid]] or [[Invalid]], the later must contain a descriptive list of error messages.
   */
  def check(toCheck: Config, actualConfig: Config): ConfigValidation
}

object JoinConfigCompatChecker {

  /**
   * Checks that all `requiredKeys` are available in `toCheck` Config.
   *
   * @param requiredKeys - a Seq of required keys
   * @param toCheck - the Config instance to be checked
   */
  def exists(requiredKeys: im.Seq[String], toCheck: Config): ConfigValidation = {
    val allKeys = toCheck.entrySet().asScala.map(_.getKey)
    // return all not found required keys
    val result =
      requiredKeys.collect {
        case requiredKey if !allKeys.contains(requiredKey) => requiredKey + " is missing"
      }

    if (result.isEmpty) Valid
    else Invalid(result.to(im.Seq))
  }

  /**
   * Checks that all `requiredKeys` are available in `toCheck` Config
   * and its values match exactly the values in `currentConfig`.
   *
   * @param requiredKeys - a Seq of required keys
   * @param toCheck - the Config instance to be checked
   * @param actualConfig - the Config instance containing the expected values
   */
  def fullMatch(requiredKeys: im.Seq[String], toCheck: Config, actualConfig: Config): ConfigValidation = {

    def checkEquality = {

      def checkCompat(entry: util.Map.Entry[String, ConfigValue]) = {
        val key = entry.getKey
        actualConfig.hasPath(key) && actualConfig.getValue(key) == entry.getValue
      }

      // retrieve all incompatible keys
      // NOTE: we only check the key if effectively required
      // because config may contain more keys than required for this checker
      val incompatibleKeys =
        toCheck.entrySet().asScala.collect {
          case entry if requiredKeys.contains(entry.getKey) && !checkCompat(entry) => s"${entry.getKey} is incompatible"
        }

      if (incompatibleKeys.isEmpty) Valid
      else Invalid(incompatibleKeys.to(im.Seq))
    }

    exists(requiredKeys, toCheck) ++ checkEquality
  }

  /**
   * INTERNAL API
   * Builds a new Config object containing only the required entries defined by `requiredKeys`
   *
   * This method is used from the joining side to prepare the [[Config]] instance that will be sent over the wire.
   * We don't send the full config to avoid unnecessary data transfer, but also to avoid leaking any sensitive
   * information that users may have added to their configuration.
   */
  @InternalApi
  private[cluster] def filterWithKeys(requiredKeys: im.Seq[String], config: Config): Config = {

    val filtered =
      config.entrySet().asScala.collect {
        case e if requiredKeys.contains(e.getKey) => (e.getKey, e.getValue)
      }

    ConfigFactory.parseMap(filtered.toMap.asJava)
  }

  /**
   * INTERNAL API
   * Removes sensitive keys, as defined in 'akka.cluster.configuration-compatibility-check.sensitive-config-paths',
   * from the passed `requiredKeys` Seq.
   */
  @InternalApi
  private[cluster] def removeSensitiveKeys(
      requiredKeys: im.Seq[String],
      clusterSettings: ClusterSettings): im.Seq[String] = {
    requiredKeys.filter { key =>
      !clusterSettings.SensitiveConfigPaths.exists(s => key.startsWith(s))
    }
  }

  /**
   * INTERNAL API
   * Builds a Seq of keys using the passed `Config` not including any sensitive keys,
   * as defined in 'akka.cluster.configuration-compatibility-check.sensitive-config-paths'.
   */
  @InternalApi
  private[cluster] def removeSensitiveKeys(config: Config, clusterSettings: ClusterSettings): im.Seq[String] = {
    val existingKeys = config.entrySet().asScala.map(_.getKey).to(im.Seq)
    removeSensitiveKeys(existingKeys, clusterSettings)
  }

  /**
   * INTERNAL API
   *
   * This method loads the [[JoinConfigCompatChecker]] defined in the configuration.
   * Checkers are then combined to be used whenever a join node tries to join an existing cluster.
   */
  @InternalApi
  private[cluster] def load(system: ExtendedActorSystem, clusterSettings: ClusterSettings): JoinConfigCompatChecker = {

    val checkers =
      clusterSettings.ConfigCompatCheckers.map { fqcn =>
        system.dynamicAccess
          .createInstanceFor[JoinConfigCompatChecker](fqcn, im.Seq.empty)
          .get // can't continue if we can't load it
      }

    // composite checker
    new JoinConfigCompatChecker {
      override val requiredKeys: im.Seq[String] = {
        // Always include akka.version (used in join logging)
        "akka.version" +: checkers.flatMap(_.requiredKeys).to(im.Seq)
      }
      override def check(toValidate: Config, clusterConfig: Config): ConfigValidation =
        checkers.foldLeft(Valid: ConfigValidation) { (acc, checker) =>
          acc ++ checker.check(toValidate, clusterConfig)
        }
    }
  }
}

@DoNotInherit
sealed trait ConfigValidation {

  def ++(that: ConfigValidation) = concat(that)

  def concat(that: ConfigValidation) = {
    (this, that) match {
      case (Invalid(a), Invalid(b)) => Invalid(a ++ b)
      case (_, i @ Invalid(_))      => i
      case (i @ Invalid(_), _)      => i
      case _                        => Valid
    }
  }
}

case object Valid extends ConfigValidation {

  /**
   * Java API: get the singleton instance
   */
  def getInstance = this
}

final case class Invalid(errorMessages: im.Seq[String]) extends ConfigValidation
