package akka.actor

import com.typesafe.config.{ Config, ConfigFactory }

import scala.collection.JavaConverters._

/**
 * Singleton ActorSystem used for migration from Scala actors to Akka.
 *
 * The main difference between `MigrationSystem` and regular `ActorSystem` is that migration actors have a `Stop` policy for failure.
 * Actors created with this system will not restart by default.
 *
 * `MigrationSystem` object embeds the ActorSystem with given behavior and provides delegate methods only for factory methods and shutdown.
 * For other operations on `ActorSystem` use `MigrationSystem.system` field.
 *
 * MigrationSystem is not deamonic by default so if it is used in the project it needs to be shut down when terminating the application.
 */
object MigrationSystem {

  private[this] val config =
    ConfigFactory.parseMap(Map(
      "akka.actor.provider" -> "akka.actor.MigrationLocalRefProvider").asJava)
      .withFallback(ConfigFactory.load())

  val system = ActorSystem("MigrationSystem", config)

  def actorOf(props: Props): ActorRef = system.actorOf(props)

  def actorOf(props: Props, name: String): ActorRef = system.actorOf(props, name)

  def shutdown(): Unit = system.shutdown()

}
