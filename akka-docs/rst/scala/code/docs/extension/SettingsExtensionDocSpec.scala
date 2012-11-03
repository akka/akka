/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.extension

//#imports
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit

//#imports

import akka.actor.Actor
import akka.testkit.AkkaSpec

//#extension
class SettingsImpl(config: Config) extends Extension {
  val DbUri: String = config.getString("myapp.db.uri")
  val CircuitBreakerTimeout: Duration =
    Duration(config.getMilliseconds("myapp.circuit-breaker.timeout"),
      TimeUnit.MILLISECONDS)
}
//#extension

//#extensionid
object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {

  override def lookup = Settings

  override def createExtension(system: ExtendedActorSystem) =
    new SettingsImpl(system.settings.config)
}
//#extensionid

object SettingsExtensionDocSpec {

  val config = """
    //#config
    myapp {
      db {
        uri = "mongodb://example1.com:27017,example2.com:27017"
      }
      circuit-breaker {
        timeout = 30 seconds
      }
    }
    //#config
    """

  //#extension-usage-actor

  class MyActor extends Actor {
    val settings = Settings(context.system)
    val connection = connect(settings.DbUri, settings.CircuitBreakerTimeout)

    //#extension-usage-actor
    def receive = {
      case someMessage ⇒
    }

    def connect(dbUri: String, circuitBreakerTimeout: Duration) = {
      "dummy"
    }
  }

}

class SettingsExtensionDocSpec extends AkkaSpec(SettingsExtensionDocSpec.config) {

  "demonstrate how to create application specific settings extension in Scala" in {
    //#extension-usage
    val dbUri = Settings(system).DbUri
    val circuitBreakerTimeout = Settings(system).CircuitBreakerTimeout
    //#extension-usage
  }

}
