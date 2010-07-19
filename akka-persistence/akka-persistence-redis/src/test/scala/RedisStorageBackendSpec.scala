package se.scalablesolutions.akka.persistence.redis

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.serialization.Serializable
import se.scalablesolutions.akka.serialization.Serializer._

import sbinary._
import sbinary.Operations._
import sbinary.DefaultProtocol._
import java.util.{Calendar, Date}

import RedisStorageBackend._

@RunWith(classOf[JUnitRunner])
class RedisStorageBackendSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  override def beforeAll {
    flushDB
    println("** destroyed database")
  }

  override def afterAll {
    flushDB
    println("** destroyed database")
  }

  describe("Store and query in maps") {
    it("should enter 4 entries in redis for transaction T-1") {
      insertMapStorageEntryFor("T-1", "debasish.company".getBytes, "anshinsoft".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.language".getBytes, "java".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.age".getBytes, "44".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.spouse".getBytes, "paramita".getBytes)

      getMapStorageSizeFor("T-1") should equal(4)
      new String(getMapStorageEntryFor(
        "T-1", "debasish.language".getBytes).get) should equal("java")
    }

    it("should enter key/values for another transaction T-2") {
      insertMapStorageEntryFor("T-2", "debasish.age".getBytes, "49".getBytes)
      insertMapStorageEntryFor("T-2", "debasish.spouse".getBytes, "paramita".getBytes)
      getMapStorageSizeFor("T-1") should equal(4)
      getMapStorageSizeFor("T-2") should equal(2)
    }

    it("should remove map storage for T-1 and T2") {
      removeMapStorageFor("T-1")
      removeMapStorageFor("T-2")
    }
  }

  describe("Store and query long value in maps") {
    it("should enter 4 entries in redis for transaction T-1") {
    val d = Calendar.getInstance.getTime.getTime
      insertMapStorageEntryFor("T-11", "debasish".getBytes,
        toByteArray[Long](d))

      getMapStorageSizeFor("T-11") should equal(1)
      fromByteArray[Long](getMapStorageEntryFor("T-11", "debasish".getBytes).get) should equal(d)
    }

    it("should remove map storage for T-1 and T2") {
      removeMapStorageFor("T-11")
    }
  }

  describe("Range query in maps") {
    it("should enter 7 entries in redis for transaction T-5") {
      insertMapStorageEntryFor("T-5", "trade.refno".getBytes, "R-123".getBytes)
      insertMapStorageEntryFor("T-5", "trade.instrument".getBytes, "IBM".getBytes)
      insertMapStorageEntryFor("T-5", "trade.type".getBytes, "BUY".getBytes)
      insertMapStorageEntryFor("T-5", "trade.account".getBytes, "A-123".getBytes)
      insertMapStorageEntryFor("T-5", "trade.amount".getBytes, "1000000".getBytes)
      insertMapStorageEntryFor("T-5", "trade.quantity".getBytes, "1000".getBytes)
      insertMapStorageEntryFor("T-5", "trade.broker".getBytes, "Nomura".getBytes)
      getMapStorageSizeFor("T-5") should equal(7)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        None, 3).map(e => (new String(e._1), new String(e._2))).size should equal(3)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        Some("trade.type".getBytes), 3).map(e => (new String(e._1), new String(e._2))).size should equal(6)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        Some("trade.type".getBytes), 0).map(e => (new String(e._1), new String(e._2))).size should equal(6)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        None, 0).map(e => (new String(e._1), new String(e._2))).size should equal(7)
    }
    it("should remove map storage for T5") {
      removeMapStorageFor("T-5")
    }
  }

  describe("Store and query objects in maps") {
    import NameSerialization._
    it("should write a Name object and fetch it properly") {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))

      insertMapStorageEntryFor("T-31", "debasish".getBytes, toByteArray[Name](n))
      getMapStorageSizeFor("T-31") should equal(1)
      fromByteArray[Name](getMapStorageEntryFor("T-31", "debasish".getBytes).get) should equal(n)
    }
    it("should remove map storage for T31") {
      removeMapStorageFor("T-31")
    }
  }

  describe("Store and query in vectors") {
    it("should write 4 entries in a vector for transaction T-3") {
      insertVectorStorageEntryFor("T-3", "debasish".getBytes)
      insertVectorStorageEntryFor("T-3", "maulindu".getBytes)
      insertVectorStorageEntryFor("T-3", "1200".getBytes)

      val dt = Calendar.getInstance.getTime.getTime
      insertVectorStorageEntryFor("T-3", toByteArray[Long](dt))
      getVectorStorageSizeFor("T-3") should equal(4)
      fromByteArray[Long](getVectorStorageEntryFor("T-3", 0)) should equal(dt)
      getVectorStorageSizeFor("T-3") should equal(4)
    }
  }

  describe("Store and query objects in vectors") {
    import NameSerialization._
    it("should write a Name object and fetch it properly") {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))

      insertVectorStorageEntryFor("T-31", toByteArray[Name](n))
      getVectorStorageSizeFor("T-31") should equal(1)
      fromByteArray[Name](getVectorStorageEntryFor("T-31", 0)) should equal(n)
    }
  }

  describe("Store and query in ref") {
    import NameSerialization._
    it("should write 4 entries in 4 refs for transaction T-4") {
      insertRefStorageFor("T-4", "debasish".getBytes)
      insertRefStorageFor("T-4", "maulindu".getBytes)

      insertRefStorageFor("T-4", "1200".getBytes)
      new String(getRefStorageFor("T-4").get) should equal("1200")
    }
    it("should write a Name object and fetch it properly") {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))
      insertRefStorageFor("T-4", toByteArray[Name](n))
      fromByteArray[Name](getRefStorageFor("T-4").get) should equal(n)
    }
  }

  describe("store and query in queue") {
    it("should give proper queue semantics") {
      enqueue("T-5", "alan kay".getBytes)
      enqueue("T-5", "alan turing".getBytes)
      enqueue("T-5", "richard stallman".getBytes)
      enqueue("T-5", "yukihiro matsumoto".getBytes)
      enqueue("T-5", "claude shannon".getBytes)
      enqueue("T-5", "linus torvalds".getBytes)

      RedisStorageBackend.size("T-5") should equal(6)

      new String(dequeue("T-5").get) should equal("alan kay")
      new String(dequeue("T-5").get) should equal("alan turing")

      RedisStorageBackend.size("T-5") should equal(4)

      val l = peek("T-5", 0, 3)
      l.size should equal(3)
      new String(l(0)) should equal("richard stallman")
      new String(l(1)) should equal("yukihiro matsumoto")
      new String(l(2)) should equal("claude shannon")
    }
    it("should write a Name object and fetch it properly") {
      import NameSerialization._
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))
      enqueue("T-5-1", toByteArray[Name](n))
      fromByteArray[Name](peek("T-5-1", 0, 1).head) should equal(n)
      fromByteArray[Name](dequeue("T-5-1").get) should equal(n)
    }
  }

  describe("store and query in sorted set") {
    it("should give proper sorted set semantics") {
      zadd("hackers", "1965", "yukihiro matsumoto".getBytes)
      zadd("hackers", "1953", "richard stallman".getBytes)
      zadd("hackers", "1916", "claude shannon".getBytes)
      zadd("hackers", "1969", "linus torvalds".getBytes)
      zadd("hackers", "1940", "alan kay".getBytes)
      zadd("hackers", "1912", "alan turing".getBytes)

      zcard("hackers") should equal(6)

      zscore("hackers", "alan turing".getBytes).get should equal(1912.0f)
      zscore("hackers", "richard stallman".getBytes).get should equal(1953.0f)
      zscore("hackers", "claude shannon".getBytes).get should equal(1916.0f)
      zscore("hackers", "linus torvalds".getBytes).get should equal(1969.0f)

      val s: List[Array[Byte]] = zrange("hackers", 0, 2)
      s.size should equal(3)
      s.map(new String(_)) should equal(List("alan turing", "claude shannon", "alan kay"))

      var sorted: List[String] =
        List("alan turing", "claude shannon", "alan kay", "richard stallman", "yukihiro matsumoto", "linus torvalds")

      val t: List[Array[Byte]] = zrange("hackers", 0, -1)
      t.size should equal(6)
      t.map(new String(_)) should equal(sorted)

      val u: List[(Array[Byte], Float)] = zrangeWithScore("hackers", 0, -1)
      u.size should equal(6)
      u.map{ case (e, s) => new String(e) } should equal(sorted)
    }
  }
}

object NameSerialization {
  implicit object DateFormat extends Format[Date] {
    def reads(in : Input) =
      new Date(read[Long](in))

    def writes(out: Output, value: Date) =
      write[Long](out, value.getTime)
  }

  case class Name(id: Int, name: String,
    address: String, dateOfBirth: Date, dateDied: Option[Date])

  implicit val NameFormat: Format[Name] =
    asProduct5(Name)(Name.unapply(_).get)
}
