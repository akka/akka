/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.ws

import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.{ Suite, BeforeAndAfterAll }

trait WithMaterializerSpec extends BeforeAndAfterAll { _: Suite ⇒
  lazy val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit lazy val system = ActorSystem(getClass.getSimpleName, testConf)

  implicit lazy val materializer = ActorFlowMaterializer()
  override def afterAll() = system.shutdown()
}