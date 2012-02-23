/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem

class MongoBasedMailboxSettings(val system: ActorSystem, val userConfig: Config) extends DurableMailboxSettings {

  def name = "mongodb"

  val config = initialize

  import config._

  val MongoURI = getString("uri")
  val WriteTimeout = Duration(config.getMilliseconds("timeout.write"), MILLISECONDS)
  val ReadTimeout = Duration(config.getMilliseconds("timeout.read"), MILLISECONDS)

}