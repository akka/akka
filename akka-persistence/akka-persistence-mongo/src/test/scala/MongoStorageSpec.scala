package akka.persistence.mongo

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import java.util.NoSuchElementException

@RunWith(classOf[JUnitRunner])
class MongoStorageSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterEach {

  override def beforeEach {
    MongoStorageBackend.drop
  }

  override def afterEach {
    MongoStorageBackend.drop
  }

  describe("persistent maps") {
    it("should insert with single key and value") {
      import MongoStorageBackend._

      insertMapStorageEntryFor("t1", "odersky".getBytes, "scala".getBytes)
      insertMapStorageEntryFor("t1", "gosling".getBytes, "java".getBytes)
      insertMapStorageEntryFor("t1", "stroustrup".getBytes, "c++".getBytes)
      getMapStorageSizeFor("t1") should equal(3)
      new String(getMapStorageEntryFor("t1", "odersky".getBytes).get) should equal("scala")
      new String(getMapStorageEntryFor("t1", "gosling".getBytes).get) should equal("java")
      new String(getMapStorageEntryFor("t1", "stroustrup".getBytes).get) should equal("c++")
      getMapStorageEntryFor("t1", "torvalds".getBytes) should equal(None)
    }

    it("should insert with multiple keys and values") {
      import MongoStorageBackend._

      val l = List(("stroustrup", "c++"), ("odersky", "scala"), ("gosling", "java"))
      insertMapStorageEntriesFor("t1", l.map { case (k, v) => (k.getBytes, v.getBytes) })
      getMapStorageSizeFor("t1") should equal(3)
      new String(getMapStorageEntryFor("t1", "stroustrup".getBytes).get) should equal("c++")
      new String(getMapStorageEntryFor("t1", "gosling".getBytes).get) should equal("java")
      new String(getMapStorageEntryFor("t1", "odersky".getBytes).get) should equal("scala")
      getMapStorageEntryFor("t1", "torvalds".getBytes) should equal(None)

      getMapStorageEntryFor("t2", "torvalds".getBytes) should equal(None)

      getMapStorageFor("t1").map { case (k, v) => (new String(k), new String(v)) } should equal (l)

      removeMapStorageFor("t1", "gosling".getBytes)
      getMapStorageSizeFor("t1") should equal(2)

      removeMapStorageFor("t1")
      getMapStorageSizeFor("t1") should equal(0)
    }

    it("should do proper range queries") {
      import MongoStorageBackend._
      val l = List(
        ("bjarne stroustrup", "c++"),
        ("martin odersky", "scala"),
        ("james gosling", "java"),
        ("yukihiro matsumoto", "ruby"),
        ("slava pestov", "factor"),
        ("rich hickey", "clojure"),
        ("ola bini", "ioke"),
        ("dennis ritchie", "c"),
        ("larry wall", "perl"),
        ("guido van rossum", "python"),
        ("james strachan", "groovy"))
      insertMapStorageEntriesFor("t1", l.map { case (k, v) => (k.getBytes, v.getBytes) })
      getMapStorageSizeFor("t1") should equal(l.size)
      getMapStorageRangeFor("t1", None, None, 100).map { case (k, v) => (new String(k), new String(v)) } should equal(l.sortWith(_._1 < _._1))
      getMapStorageRangeFor("t1", None, None, 5).map { case (k, v) => (new String(k), new String(v)) }.size should equal(5)
    }
  }

  describe("persistent vectors") {
    it("should insert a single value") {
      import MongoStorageBackend._

      insertVectorStorageEntryFor("t1", "martin odersky".getBytes)
      insertVectorStorageEntryFor("t1", "james gosling".getBytes)
      new String(getVectorStorageEntryFor("t1", 0)) should equal("james gosling")
      new String(getVectorStorageEntryFor("t1", 1)) should equal("martin odersky")
    }

    it("should insert multiple values") {
      import MongoStorageBackend._

      insertVectorStorageEntryFor("t1", "martin odersky".getBytes)
      insertVectorStorageEntryFor("t1", "james gosling".getBytes)
      insertVectorStorageEntriesFor("t1", List("ola bini".getBytes, "james strachan".getBytes, "dennis ritchie".getBytes))
      new String(getVectorStorageEntryFor("t1", 0)) should equal("ola bini")
      new String(getVectorStorageEntryFor("t1", 1)) should equal("james strachan")
      new String(getVectorStorageEntryFor("t1", 2)) should equal("dennis ritchie")
      new String(getVectorStorageEntryFor("t1", 3)) should equal("james gosling")
      new String(getVectorStorageEntryFor("t1", 4)) should equal("martin odersky")
    }

    it("should fetch a range of values") {
      import MongoStorageBackend._

      insertVectorStorageEntryFor("t1", "martin odersky".getBytes)
      insertVectorStorageEntryFor("t1", "james gosling".getBytes)
      getVectorStorageSizeFor("t1") should equal(2)
      insertVectorStorageEntriesFor("t1", List("ola bini".getBytes, "james strachan".getBytes, "dennis ritchie".getBytes))
      getVectorStorageRangeFor("t1", None, None, 100).map(new String(_)) should equal(List("ola bini", "james strachan", "dennis ritchie", "james gosling", "martin odersky"))
      getVectorStorageRangeFor("t1", Some(0), Some(5), 100).map(new String(_)) should equal(List("ola bini", "james strachan", "dennis ritchie", "james gosling", "martin odersky"))
      getVectorStorageRangeFor("t1", Some(2), Some(5), 100).map(new String(_)) should equal(List("dennis ritchie", "james gosling", "martin odersky"))

      getVectorStorageSizeFor("t1") should equal(5)
    }

    it("should insert and query complex structures") {
      import MongoStorageBackend._
      import sjson.json.DefaultProtocol._
      import sjson.json.JsonSerialization._

      // a list[AnyRef] should be added successfully
      val l = List("ola bini".getBytes, tobinary(List(100, 200, 300)), tobinary(List(1, 2, 3)))

      // for id = t1
      insertVectorStorageEntriesFor("t1", l)
      new String(getVectorStorageEntryFor("t1", 0)) should equal("ola bini")
      frombinary[List[Int]](getVectorStorageEntryFor("t1", 1)) should equal(List(100, 200, 300))
      frombinary[List[Int]](getVectorStorageEntryFor("t1", 2)) should equal(List(1, 2, 3))

      getVectorStorageSizeFor("t1") should equal(3)

      // some more for id = t1
      val m = List(tobinary(Map(1 -> "dg", 2 -> "mc", 3 -> "nd")), tobinary(List("martin odersky", "james gosling")))
      insertVectorStorageEntriesFor("t1", m)

      // size should add up
      getVectorStorageSizeFor("t1") should equal(5)

      // now for a diff id
      insertVectorStorageEntriesFor("t2", l)
      getVectorStorageSizeFor("t2") should equal(3)
    }
  }

  describe("persistent refs") {
    it("should insert a ref") {
      import MongoStorageBackend._

      insertRefStorageFor("t1", "martin odersky".getBytes)
      new String(getRefStorageFor("t1").get) should equal("martin odersky")
      insertRefStorageFor("t1", "james gosling".getBytes)
      new String(getRefStorageFor("t1").get) should equal("james gosling")
      getRefStorageFor("t2") should equal(None)
    }
  }
}
