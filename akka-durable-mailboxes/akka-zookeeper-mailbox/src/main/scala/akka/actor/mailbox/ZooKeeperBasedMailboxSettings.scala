/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorSystem

class ZooKeeperBasedMailboxSettings(val system: ActorSystem, val userConfig: Config) extends DurableMailboxSettings {

  def name = "zookeeper"

  val config = initialize

  import config._

  val ZkServerAddresses = getString("server-addresses")
  val SessionTimeout = Duration(getMilliseconds("session-timeout"), MILLISECONDS)
  val ConnectionTimeout = Duration(getMilliseconds("connection-timeout"), MILLISECONDS)
  val BlockingQueue = getBoolean("blocking-queue")

}