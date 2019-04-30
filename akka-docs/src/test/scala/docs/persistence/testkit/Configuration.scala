/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.testkit
import akka.actor.ActorSystem
import akka.persistence.testkit.{ PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin }
import akka.persistence.testkit.scaladsl.{ PersistenceTestKit, SnapshotTestKit }
import com.typesafe.config.ConfigFactory

object TestKitConf {

  //#testkit-conf

  val yourConfiguration = ConfigFactory.defaultApplication()

  implicit val system = ActorSystem("test-system", PersistenceTestKitPlugin.config.withFallback(yourConfiguration))

  val testKit = new PersistenceTestKit

  //#testkit-conf

}

object SnapshotConf {

  //#snapshot-conf

  val yourConfiguration = ConfigFactory.defaultApplication()

  implicit val system =
    ActorSystem("test-system", PersistenceTestKitSnapshotPlugin.config.withFallback(yourConfiguration))

  val testKit = new SnapshotTestKit

  //#snapshot-conf

}
