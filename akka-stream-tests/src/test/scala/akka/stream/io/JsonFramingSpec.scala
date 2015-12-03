/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream.ActorMaterializer
import akka.stream.impl.JsonBracketCounting
import akka.stream.io.Framing.FramingException
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._

class JsonFramingSpec extends AkkaSpec with ScalaFutures {

  implicit val mat = ActorMaterializer()

  "collecting multiple json" should {
    "parse json array" in {
      val input =
        """
          |[
          | { "name" : "john" },
          | { "name" : "jack" },
          | { "name" : "katie" }
          |]
          |""".stripMargin // also should complete once notices end of array

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name" : "john" }""".stripMargin,
        """{ "name" : "jack" }""".stripMargin,
        """{ "name" : "katie" }""".stripMargin)
    }

    "emit single json element from string" in {
      val input =
        """| { "name": "john" }
           | { "name": "jack" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .take(1)
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      Await.result(result, 3.seconds) shouldBe Seq("""{ "name": "john" }""".stripMargin)
    }

    "parse line delimited" in {
      val input =
        """| { "name": "john" }
           | { "name": "jack" }
           | { "name": "katie" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      Await.result(result, 3.seconds) shouldBe Seq(
        """{ "name": "john" }""".stripMargin,
        """{ "name": "jack" }""".stripMargin,
        """{ "name": "katie" }""".stripMargin)
    }

    "parse comma delimited" in {
      val input =
        """
          | { "name": "john" }, { "name": "jack" }, { "name": "katie" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john" }""".stripMargin,
        """{ "name": "jack" }""",
        """{ "name": "katie" }""")
    }

    "parse chunks successfully" in {
      val input: Seq[ByteString] = Seq(
        """
          |[
          |  { "name": "john"""".stripMargin,
        """
          |},
        """.stripMargin,
        """{ "na""",
        """me": "jack""",
        """"}]"""").map(ByteString(_))

      val result = Source.apply(input)
        .via(Framing.json(Int.MaxValue))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      result.futureValue shouldBe Seq(
        """{ "name": "john"
          |}""".stripMargin,
        """{ "name": "jack"}""")
    }
  }

  // TODO fold these specs into the previous section
  "collecting json buffer" when {
    "nothing is supplied" should {
      "return nothing" in {
        val buffer = new JsonBracketCounting()
        buffer.poll() should ===(None)
      }
    }

    "valid json is supplied" which {
      "has one object" should {
        "successfully parse empty object" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{}"""))
          buffer.poll().get.utf8String shouldBe """{}"""
        }

        "successfully parse single field having string value" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "name": "john"}"""))
          buffer.poll().get.utf8String shouldBe """{ "name": "john"}"""
        }

        "successfully parse single field having string value containing space" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "name": "john doe"}"""))
          buffer.poll().get.utf8String shouldBe """{ "name": "john doe"}"""
        }

        "successfully parse single field having string value containing curly brace" in {
          val buffer = new JsonBracketCounting()

          buffer.offer(ByteString("""{ "name": "john{"""))
          buffer.offer(ByteString("}"))
          buffer.offer(ByteString("\""))
          buffer.offer(ByteString("}"))

          buffer.poll().get.utf8String shouldBe """{ "name": "john{}"}"""
        }

        "successfully parse single field having string value containing curly brace and escape character" in {
          val buffer = new JsonBracketCounting()

          buffer.offer(ByteString("""{ "name": "john"""))
          buffer.offer(ByteString("\\\""))
          buffer.offer(ByteString("{"))
          buffer.offer(ByteString("}"))
          buffer.offer(ByteString("\\\""))
          buffer.offer(ByteString(" "))
          buffer.offer(ByteString("hey"))
          buffer.offer(ByteString("\""))

          buffer.offer(ByteString("}"))
          buffer.poll().get.utf8String shouldBe """{ "name": "john\"{}\" hey"}"""
        }

        "successfully parse single field having integer value" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "age": 101}"""))
          buffer.poll().get.utf8String shouldBe """{ "age": 101}"""
        }

        "successfully parse single field having decimal value" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "age": 101}"""))
          buffer.poll().get.utf8String shouldBe """{ "age": 101}"""
        }

        "successfully parse single field having nested object" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(
            """
              |{  "name": "john",
              |   "age": 101,
              |   "address": {
              |     "street": "Straight Street",
              |     "postcode": 1234
              |   }
              |}
              | """.stripMargin))
          buffer.poll().get.utf8String shouldBe """{  "name": "john",
                                                      |   "age": 101,
                                                      |   "address": {
                                                      |     "street": "Straight Street",
                                                      |     "postcode": 1234
                                                      |   }
                                                      |}""".stripMargin
        }

        "successfully parse single field having multiple level of nested object" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(
            """
              |{  "name": "john",
              |   "age": 101,
              |   "address": {
              |     "street": {
              |       "name": "Straight",
              |       "type": "Avenue"
              |     },
              |     "postcode": 1234
              |   }
              |}
              | """.stripMargin))
          buffer.poll().get.utf8String shouldBe """{  "name": "john",
                                                      |   "age": 101,
                                                      |   "address": {
                                                      |     "street": {
                                                      |       "name": "Straight",
                                                      |       "type": "Avenue"
                                                      |     },
                                                      |     "postcode": 1234
                                                      |   }
                                                      |}""".stripMargin
        }
      }

      "has nested array" should {
        "successfully parse" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(
            """
              |{  "name": "john",
              |   "things": [
              |     1,
              |     "hey",
              |     3,
              |     "there"
              |   ]
              |}
              | """.stripMargin))
          buffer.poll().get.utf8String shouldBe """{  "name": "john",
                                                      |   "things": [
                                                      |     1,
                                                      |     "hey",
                                                      |     3,
                                                      |     "there"
                                                      |   ]
                                                      |}""".stripMargin
        }
      }

      "has complex object graph" should {
        "successfully parse" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(
            """
              |{
              |  "name": "john",
              |  "addresses": [
              |    {
              |      "street": "3 Hopson Street",
              |      "postcode": "ABC-123",
              |      "tags": ["work", "office"],
              |      "contactTime": [
              |        {"time": "0900-1800", "timezone", "UTC"}
              |      ]
              |    },
              |    {
              |      "street": "12 Adielie Road",
              |      "postcode": "ZZY-888",
              |      "tags": ["home"],
              |      "contactTime": [
              |        {"time": "0800-0830", "timezone", "UTC"},
              |        {"time": "1800-2000", "timezone", "UTC"}
              |      ]
              |    }
              |  ]
              |}
              | """.stripMargin))

          buffer.poll().get.utf8String shouldBe """{
                                                      |  "name": "john",
                                                      |  "addresses": [
                                                      |    {
                                                      |      "street": "3 Hopson Street",
                                                      |      "postcode": "ABC-123",
                                                      |      "tags": ["work", "office"],
                                                      |      "contactTime": [
                                                      |        {"time": "0900-1800", "timezone", "UTC"}
                                                      |      ]
                                                      |    },
                                                      |    {
                                                      |      "street": "12 Adielie Road",
                                                      |      "postcode": "ZZY-888",
                                                      |      "tags": ["home"],
                                                      |      "contactTime": [
                                                      |        {"time": "0800-0830", "timezone", "UTC"},
                                                      |        {"time": "1800-2000", "timezone", "UTC"}
                                                      |      ]
                                                      |    }
                                                      |  ]
                                                      |}""".stripMargin
        }
      }

      "has multiple fields" should {
        "parse successfully" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "name": "john", "age": 101}"""))
          buffer.poll().get.utf8String shouldBe """{ "name": "john", "age": 101}"""
        }

        "parse successfully despite valid whitespaces around json" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(
            """
              |
              |
              |{"name":   "john"
              |, "age": 101}""".stripMargin))
          buffer.poll().get.utf8String shouldBe
            """{"name":   "john"
              |, "age": 101}""".stripMargin
        }
      }

      "has multiple objects" should {
        "pops the right object as buffer is filled" in {
          val input =
            """
              |  {
              |    "name": "john",
              |    "age": 32
              |  },
              |  {
              |    "name": "katie",
              |    "age": 25
              |  }
            """.stripMargin

          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString(input))

          buffer.poll().get.utf8String shouldBe
            """{
              |    "name": "john",
              |    "age": 32
              |  }""".stripMargin
          buffer.poll().get.utf8String shouldBe
            """{
              |    "name": "katie",
              |    "age": 25
              |  }""".stripMargin
          buffer.poll() should ===(None)

          buffer.offer(ByteString("""{"name":"jenkins","age": """))
          buffer.poll() should ===(None)

          buffer.offer(ByteString("65 }"))
          buffer.poll().get.utf8String shouldBe """{"name":"jenkins","age": 65 }"""
        }
      }

      "returns none until valid json is encountered" in {
        val buffer = new JsonBracketCounting()

        """{ "name": "john"""".stripMargin.foreach {
          c ⇒
            buffer.offer(ByteString(c))
            buffer.poll() should ===(None)
        }

        buffer.offer(ByteString("}"))
        buffer.poll().get.utf8String shouldBe """{ "name": "john"}"""
      }

      "invalid json is supplied" should {
        "fail if it's broken from the start" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""THIS IS NOT VALID { "name": "john"}"""))
          a[FramingException] shouldBe thrownBy { buffer.poll() }
        }

        "fail if it's broken at the end" in {
          val buffer = new JsonBracketCounting()
          buffer.offer(ByteString("""{ "name": "john"} THIS IS NOT VALID"""))
          buffer.poll() // first emitting the valid element
          a[FramingException] shouldBe thrownBy { buffer.poll() }
        }
      }
    }

    "fail on too large initial object" in {
      val input =
        """
          | { "name": "john" }, { "name": "jack" }
        """.stripMargin

      val result = Source.single(ByteString(input))
        .via(Framing.json(5))
        .runFold(Seq.empty[String]) {
          case (acc, entry) ⇒ acc ++ Seq(entry.utf8String)
        }

      a[FramingException] shouldBe thrownBy {
        Await.result(result, 3.seconds)
      }
    }

    "fail when 2nd object is too large" in {
      val input = List(
        """{ "name": "john" }""",
        """{ "name": "jack" }""",
        """{ "name": "very very long name somehow. how did this happen?" }""").map(s ⇒ ByteString(s))

      val probe = Source(input)
        .via(Framing.json(48))
        .runWith(TestSink.probe)

      probe.ensureSubscription()
      probe
        .request(1)
        .expectNext(ByteString("""{ "name": "john" }""")) // FIXME we should not impact the given json in Framing
        .request(1)
        .expectNext(ByteString("""{ "name": "jack" }"""))
        .request(1)
        .expectError().getMessage should include("exceeded")
    }
  }
}