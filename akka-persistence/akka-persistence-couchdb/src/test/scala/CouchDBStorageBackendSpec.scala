package akka.persistence.couchdb

import org.specs._
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.serialization.Serializable
import akka.serialization.Serializer._

import CouchDBStorageBackend._
import sbinary._
import sbinary.Operations._
import sbinary.DefaultProtocol._
import java.util.{Calendar, Date}

@RunWith(classOf[JUnitRunner])
class CouchDBStorageBackendSpec extends Specification {
  doBeforeSpec {
    CouchDBStorageBackend.create()
  }

  doAfterSpec {
    CouchDBStorageBackend.drop()
  }

  "CouchDBStorageBackend store and query in map" should {
    "enter 4 entries for transaction T-1" in {
      insertMapStorageEntryFor("T-1", "debasish.company".getBytes, "anshinsoft".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.language".getBytes, "java".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.age".getBytes, "44".getBytes)
      insertMapStorageEntryFor("T-1", "debasish.spouse".getBytes, "paramita".getBytes)

      getMapStorageSizeFor("T-1") mustEqual(4)
      new String(getMapStorageEntryFor("T-1", "debasish.language".getBytes).get) mustEqual("java")
      getMapStorageSizeFor("T-1") mustEqual(4)
    }

    "enter key/values for another transaction T-2" in {
      insertMapStorageEntryFor("T-2", "debasish.age".getBytes, "49".getBytes)
      insertMapStorageEntryFor("T-2", "debasish.spouse".getBytes, "paramita".getBytes)
      getMapStorageSizeFor("T-2") mustEqual(2)
    }

    "remove map storage for T-99" in {
      insertMapStorageEntryFor("T-99", "provider".getBytes, "googleapp".getBytes)
      insertMapStorageEntryFor("T-99", "quota".getBytes, "100mb".getBytes)
      getMapStorageSizeFor("T-99") mustEqual(2)
      removeMapStorageFor("T-99", "quota".getBytes)
      getMapStorageSizeFor("T-99") mustEqual(1)
      getMapStorageEntryFor("T-99", "quota".getBytes) mustEqual(None)
    }

    "remove map storage for T-1 and T2" in {
      removeMapStorageFor("T-1")
      removeMapStorageFor("T-2")
      getMapStorageSizeFor("T-1") mustEqual(0)
      getMapStorageSizeFor("T-2") mustEqual(0)
    }
  }

  "CouchDBStorageBackend store and query long value in map" should {
    "enter 4 entries for transaction T-11" in {
      val d = Calendar.getInstance.getTime.getTime
        insertMapStorageEntryFor("T-11", "steve".getBytes, toByteArray[Long](d))
        insertMapStorageEntryFor("T-11", "john".getBytes, toByteArray[Long](d + 1))
        insertMapStorageEntryFor("T-11", "bill".getBytes, toByteArray[Long](d * 999))
        insertMapStorageEntryFor("T-11", "david".getBytes, toByteArray[Long](d / 2))

        getMapStorageSizeFor("T-11") mustEqual(4)
        fromByteArray[Long](getMapStorageEntryFor("T-11", "steve".getBytes).get) mustEqual(d)
    }
  }


  "Range query in maps" should {
    "enter 7 entries in couchdb for transaction T-5" in {
      insertMapStorageEntryFor("T-5", "trade.refno".getBytes, "R-123".getBytes)
      insertMapStorageEntryFor("T-5", "trade.instrument".getBytes, "IBM".getBytes)
      insertMapStorageEntryFor("T-5", "trade.type".getBytes, "BUY".getBytes)
      insertMapStorageEntryFor("T-5", "trade.account".getBytes, "A-123".getBytes)
      insertMapStorageEntryFor("T-5", "trade.amount".getBytes, "1000000".getBytes)
      insertMapStorageEntryFor("T-5", "trade.quantity".getBytes, "1000".getBytes)
      insertMapStorageEntryFor("T-5", "trade.broker".getBytes, "Nomura".getBytes)
      getMapStorageSizeFor("T-5") mustEqual(7)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        None, 3).map(e => (new String(e._1), new String(e._2))).size mustEqual(3)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        Some("trade.type".getBytes), 3).map(e => (new String(e._1), new String(e._2))).size mustEqual(3)

      getMapStorageRangeFor("T-5",
        Some("trade.amount".getBytes),
        Some("trade.type".getBytes), 0).map(e => (new String(e._1), new String(e._2))).size mustEqual(6)

      getMapStorageRangeFor("T-5",
        Some("trade.account".getBytes),
        None, 0).map(e => (new String(e._1), new String(e._2))).size mustEqual(7)

      removeMapStorageFor("T-5")
    }
  }

  "Store and query objects in maps" should {
    import NameSerialization._
    "write a Name object and fetch it properly" in {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))

      insertMapStorageEntryFor("T-31", "debasish".getBytes, toByteArray[Name](n))
      getMapStorageSizeFor("T-31") mustEqual(1)
      fromByteArray[Name](getMapStorageEntryFor("T-31", "debasish".getBytes).getOrElse(Array[Byte]())) mustEqual(n)
      removeMapStorageFor("T-31")
    }
  }

  "Store and query in vectors" should {
    "write 4 entries in a vector for transaction T-3" in {
      insertVectorStorageEntryFor("T-3", "debasish".getBytes)
      insertVectorStorageEntryFor("T-3", "maulindu".getBytes)
      insertVectorStorageEntryFor("T-3", "1200".getBytes)

      val dt = Calendar.getInstance.getTime.getTime
      insertVectorStorageEntryFor("T-3", toByteArray[Long](dt))
      getVectorStorageSizeFor("T-3") mustEqual(4)
      fromByteArray[Long](getVectorStorageEntryFor("T-3", 0)) mustEqual(dt)
      getVectorStorageSizeFor("T-3") mustEqual(4)
    }
  }

  "Store and query objects in vectors" should {
    import NameSerialization._
    "write a Name object and fetch it properly" in {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))

      insertVectorStorageEntryFor("T-31", toByteArray[Name](n))
      getVectorStorageSizeFor("T-31") mustEqual(1)
      fromByteArray[Name](getVectorStorageEntryFor("T-31", 0)) mustEqual(n)
    }
  }

  "Store and query in ref" should {
    import NameSerialization._
    "write 4 entries in 4 refs for transaction T-4" in {
      insertRefStorageFor("T-4", "debasish".getBytes)
      insertRefStorageFor("T-4", "maulindu".getBytes)

      insertRefStorageFor("T-4", "1200".getBytes)
      new String(getRefStorageFor("T-4").get) mustEqual("1200")
    }

    "write a Name object and fetch it properly" in {
      val dtb = Calendar.getInstance.getTime
      val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))
      insertRefStorageFor("T-4", toByteArray[Name](n))
      fromByteArray[Name](getRefStorageFor("T-4").get) mustEqual(n)
    }
  }

  "Mix the 3 different types storage with the same name" should {
    "work independently without inference each other" in {
      insertVectorStorageEntryFor("SameName", "v1".getBytes)
      insertMapStorageEntryFor("SameName", "vector".getBytes, "map_value_v".getBytes)
      insertVectorStorageEntryFor("SameName", "v2".getBytes)
      insertMapStorageEntryFor("SameName", "ref".getBytes, "map_value_r".getBytes)
      insertVectorStorageEntryFor("SameName", "v3".getBytes)
      insertRefStorageFor("SameName", "I am a ref!".getBytes)

      getMapStorageSizeFor("SameName") mustEqual(2)
      new String(getMapStorageEntryFor("SameName", "vector".getBytes).get) mustEqual("map_value_v")
      new String(getMapStorageEntryFor("SameName", "ref".getBytes).get) mustEqual("map_value_r")
      getVectorStorageSizeFor("SameName") mustEqual(3)
      new String(getRefStorageFor("SameName").get) mustEqual("I am a ref!")
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
