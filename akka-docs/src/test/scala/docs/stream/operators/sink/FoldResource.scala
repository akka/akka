/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sink
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source

import java.net.URL

object FoldResource {
  implicit val actorSystem: ActorSystem = ???

  //#foldResource-blocking-api
  trait DBDriver {
    def create(url: URL, userName: String, password: String): Connection
  }
  trait Connection {
    def close(): Unit
  }
  trait Database {
    //blocking insert
    def doInsert(connection: Connection, query: String): DBResult = ???
  }
  trait DBResult {
    def isSuccess: Boolean
    def affectedCount: Int
  }

  //#foldResource-blocking-api
  val url: URL = ???
  val userName = "Akka"
  val password = "Hakking"
  val dbDriver: DBDriver = ???
  def foldResourceExample(): Unit = {
    //#foldResource
    //some database for JVM
    val db: Database = ???
    Source(List("INSERT INTO t1 (a, b, c) VALUES (1, 2, 3);", "INSERT INTO t2 (a, b, c) VALUES (4, 5, 6);")).runWith(
      Sink.foldResource(() => dbDriver.create(url, userName, password))(
        (connection, query) => db.doInsert(connection, query),
        _.close()))
    //#foldResource
  }
}
