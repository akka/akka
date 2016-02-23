/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.{ Suite, BeforeAndAfterAll }
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

trait WithMaterializerSpec extends BeforeAndAfterAll { _: Suite ⇒
  lazy val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit lazy val system = ActorSystem(getClass.getSimpleName, testConf)

  implicit lazy val materializer = ActorMaterializer()
  override def afterAll() = system.terminate()
}