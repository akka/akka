/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.ActorSystem
import akka.event.Logging
import akka.stream.ActorMaterializer
import akka.testkit.{ EventFilter, TestKit, TestProbe }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ Matchers, WordSpec }

class SslConfigWarningsSpec extends WordSpec with Matchers {
  val testConf: Config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.stdout-loglevel = INFO
    akka.log-dead-letters = OFF
    akka.http.server.request-timeout = infinite

    akka.ssl-config {
      loose {
        disableSNI = true
        disableHostnameVerification = true
      }
    }
    """)

  "ssl-config loose options should cause warnings to be logged" should {

    "warn if SNI is disabled globally" in {
      implicit val system = ActorSystem(getClass.getSimpleName, testConf)
      implicit val materializer = ActorMaterializer()

      val p = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[Logging.LogEvent])

      EventFilter.warning(start = "Detected that Server Name Indication (SNI) is disabled globally ", occurrences = 1) intercept {
        Http()(system)
      }

      // the very big warning shall be logged only once per actor system (extension)
      EventFilter.warning(start = "Detected that Server Name Indication (SNI) is disabled globally ", occurrences = 0) intercept {
        Http()(system)
      }

      TestKit.shutdownActorSystem(system)
    }

    "warn if hostname verification is disabled globally" in {
      implicit val system = ActorSystem(getClass.getSimpleName, testConf)
      implicit val materializer = ActorMaterializer()

      val p = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[Logging.LogEvent])

      val msgStart = "Detected that Hostname Verification is disabled globally "

      EventFilter.warning(start = msgStart, occurrences = 1) intercept { Http()(system) }

      // the very big warning shall be logged only once per actor system (extension)
      EventFilter.warning(start = msgStart, occurrences = 0) intercept { Http()(system) }

      TestKit.shutdownActorSystem(system)
    }
  }
}
