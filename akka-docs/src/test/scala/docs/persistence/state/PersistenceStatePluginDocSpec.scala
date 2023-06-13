/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.state

import akka.Done
import akka.actor.ActorSystem

import akka.testkit.TestKit
import com.typesafe.config._
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration._

//#plugin-imports
import akka.persistence._
import akka.persistence.state.scaladsl.DurableStateUpdateStore
import akka.persistence.state.scaladsl.GetObjectResult
//#plugin-imports

class PersistenceStatePluginDocSpec extends AnyWordSpec {
  {
    val providerConfig =
      """
        //#journal-plugin-config
        # Path to the journal plugin to be used
        akka.persistence.state.plugin = "my-journal"

        # My custom journal plugin
        my-journal {
          # Class name of the plugin.
          class = "docs.persistence.state.MyJournal"
          # Dispatcher for the plugin actor.
          plugin-dispatcher = "akka.actor.default-dispatcher"
        }
        //#journal-plugin-config
      """

    val system = ActorSystem(
      "PersistenceStatePluginDocSpec",
      ConfigFactory
        .parseString(providerConfig)
      //.withFallback(ConfigFactory.parseString(PersistencePluginDocSpec.config))
    )
    try {
      Persistence(system)
    } finally {
      TestKit.shutdownActorSystem(system, 10.seconds, false)
    }
  }
}

case class MyState(numbers: Seq[Int], tag: String)
class MyJournal extends DurableStateUpdateStore[MyState] {

  /**
   * Will persist the latest state. If it’s a new persistence id, the record will be inserted.
   *
   * In case of an existing persistence id, the record will be updated only if the revision
   * number of the incoming record is 1 more than the already existing record. Otherwise persist will fail.
   */
  override def upsertObject(persistenceId: String, revision: Long, value: MyState, tag: String): Future[Done] = ???

  /**
   * Deprecated. Use the deleteObject overload with revision instead.
   */
  override def deleteObject(persistenceId: String): Future[Done] = ???

  /**
   * Will delete the state by setting it to the empty state and the revision number will be incremented by 1.
   */
  override def deleteObject(persistenceId: String, revision: Long): Future[Done] = ???

  /**
   * Returns the current state for the given persistence id.
   */
  override def getObject(persistenceId: String): Future[GetObjectResult[MyState]] = ???
}
