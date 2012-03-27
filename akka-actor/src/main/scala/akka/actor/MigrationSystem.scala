package akka.actor

import com.typesafe.config.{ Config, ConfigFactory }

import scala.collection.JavaConverters._

/**
 * Singleton ActorSystem used for migration from Scala actors to Akka.
 */
object MigrationSystem extends ActorSystemImpl(
  "MigrationSystem",
  ConfigFactory.parseMap(Map(
    "akka.actor.provider" -> "akka.actor.MigrationLocalRefProvider").asJava).withFallback(ConfigFactory.load()),
  Thread.currentThread().getContextClassLoader()) {
  start()
}
