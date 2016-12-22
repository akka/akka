/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model

import java.util

import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Inside, Matchers, WordSpec }
import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import akka.stream.javadsl.Source
import akka.testkit.TestKit

import scala.compat.java8.FutureConverters

class MultipartsSpec extends WordSpec with Matchers with Inside with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
  akka.event-handlers = ["akka.testkit.TestEventListener"]
  akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)
  implicit val materializer = ActorMaterializer()
  override def afterAll() = TestKit.shutdownActorSystem(system)

  "Multiparts.createFormDataFromParts" should {
    "create a model from Multiparts.createFormDataBodyPartparts" in {
      val streamed = Multiparts.createFormDataFromParts(
        Multiparts.createFormDataBodyPart("foo", HttpEntities.create("FOO")),
        Multiparts.createFormDataBodyPart("bar", HttpEntities.create("BAR")))
      val strictCS = streamed.toStrict(1000, materializer)
      val strict = Await.result(FutureConverters.toScala(strictCS), 1.second)

      strict shouldEqual akka.http.scaladsl.model.Multipart.FormData(
        Map("foo" → akka.http.scaladsl.model.HttpEntity("FOO"), "bar" → akka.http.scaladsl.model.HttpEntity("BAR")))
    }
    "create a model from Multiparts.createFormDataFromSourceParts" in {
      val streamed = Multiparts.createFormDataFromSourceParts(Source.from(util.Arrays.asList(
        Multiparts.createFormDataBodyPart("foo", HttpEntities.create("FOO")),
        Multiparts.createFormDataBodyPart("bar", HttpEntities.create("BAR"))
      )))
      val strictCS = streamed.toStrict(1000, materializer)
      val strict = Await.result(FutureConverters.toScala(strictCS), 1.second)
      strict shouldEqual akka.http.scaladsl.model.Multipart.FormData(
        Map("foo" → akka.http.scaladsl.model.HttpEntity("FOO"), "bar" → akka.http.scaladsl.model.HttpEntity("BAR")))
    }
  }

  "Multiparts.createFormDataFromFields" should {
    "create a model from a map of fields" in {
      val fields = new util.HashMap[String, HttpEntity.Strict]
      fields.put("foo", HttpEntities.create("FOO"))
      val streamed = Multiparts.createFormDataFromFields(fields)
      val strictCS = streamed.toStrict(1000, materializer)
      val strict = Await.result(FutureConverters.toScala(strictCS), 1.second)

      strict shouldEqual akka.http.scaladsl.model.Multipart.FormData(
        Map("foo" → akka.http.scaladsl.model.HttpEntity("FOO")))
    }
  }

  "Multiparts.createStrictFormDataFromParts" should {
    "create a strict model from Multiparts.createFormDataBodyPartStrict parts" in {
      val streamed = Multiparts.createStrictFormDataFromParts(
        Multiparts.createFormDataBodyPartStrict("foo", HttpEntities.create("FOO")),
        Multiparts.createFormDataBodyPartStrict("bar", HttpEntities.create("BAR")))
      val strict = streamed

      strict shouldEqual akka.http.scaladsl.model.Multipart.FormData(
        Map("foo" → akka.http.scaladsl.model.HttpEntity("FOO"), "bar" → akka.http.scaladsl.model.HttpEntity("BAR")))
    }
  }
}
