package se.scalablesolutions.akka.persistence.mongo

import junit.framework.TestCase

import org.junit.{Test, Before}
import org.junit.Assert._
import _root_.dispatch.json._
import _root_.dispatch.json.Js._
import java.util.NoSuchElementException

@scala.reflect.BeanInfo case class Foo(no: Int, name: String)
class MongoStorageSpec extends TestCase {

  val changeSetV = new scala.collection.mutable.ArrayBuffer[AnyRef]
  val changeSetM = new scala.collection.mutable.HashMap[AnyRef, AnyRef]

  override def setUp = {
    MongoStorageBackend.coll.drop
  }

  @Test
  def testVectorInsertForTransactionId = {
    changeSetV += "debasish"   // string
    changeSetV += List(1, 2, 3) // Scala List
    changeSetV += List(100, 200)
    MongoStorageBackend.insertVectorStorageEntriesFor("U-A1", changeSetV.toList)
    assertEquals(
      3,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))
    changeSetV.clear

    // changeSetV should be reinitialized
    changeSetV += List(12, 23, 45)
    changeSetV += "maulindu"
    MongoStorageBackend.insertVectorStorageEntriesFor("U-A1", changeSetV.toList)
    assertEquals(
      5,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))

    // add more to the same changeSetV
    changeSetV += "ramanendu"
    changeSetV += Map(1 -> "dg", 2 -> "mc")

    // add for a diff transaction
    MongoStorageBackend.insertVectorStorageEntriesFor("U-A2", changeSetV.toList)
    assertEquals(
      4,
      MongoStorageBackend.getVectorStorageSizeFor("U-A2"))

    // previous transaction change set should remain same
    assertEquals(
      5,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))

    // test single element entry
    MongoStorageBackend.insertVectorStorageEntryFor("U-A1", Map(1->1, 2->4, 3->9))
    assertEquals(
      6,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))
  }

  @Test
  def testVectorFetchForKeys = {

    // initially everything 0
    assertEquals(
      0,
      MongoStorageBackend.getVectorStorageSizeFor("U-A2"))

    assertEquals(
      0,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))

    // get some stuff
    changeSetV += "debasish"
    changeSetV += List(BigDecimal(12), BigDecimal(13), BigDecimal(14))
    MongoStorageBackend.insertVectorStorageEntriesFor("U-A1", changeSetV.toList)

    assertEquals(
      2,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))

    val JsString(str) = MongoStorageBackend.getVectorStorageEntryFor("U-A1", 0).asInstanceOf[JsString]
    assertEquals("debasish", str)

    val l = MongoStorageBackend.getVectorStorageEntryFor("U-A1", 1).asInstanceOf[JsValue]
    val num_list = list ! num
    val num_list(l0) = l
    assertEquals(List(12, 13, 14), l0)

    changeSetV.clear
    changeSetV += Map(1->1, 2->4, 3->9)
    changeSetV += BigInt(2310)
    changeSetV += List(100, 200, 300)
    MongoStorageBackend.insertVectorStorageEntriesFor("U-A1", changeSetV.toList)

    assertEquals(
      5,
      MongoStorageBackend.getVectorStorageSizeFor("U-A1"))

    val r =
      MongoStorageBackend.getVectorStorageRangeFor("U-A1", Some(1), None, 3)

    assertEquals(3, r.size)
    val lr = r(0).asInstanceOf[JsValue]
    val num_list(l1) = lr
    assertEquals(List(12, 13, 14), l1)
  }

  @Test
  def testVectorFetchForNonExistentKeys = {
    try {
      MongoStorageBackend.getVectorStorageEntryFor("U-A1", 1)
      fail("should throw an exception")
    } catch {case e: NoSuchElementException => {}}

    try {
      MongoStorageBackend.getVectorStorageRangeFor("U-A1", Some(2), None, 12)
      fail("should throw an exception")
    } catch {case e: NoSuchElementException => {}}
  }

  @Test
  def testMapInsertForTransactionId = {
    fillMap
    
    // add some more to changeSet
    changeSetM += "5" -> Foo(12, "dg")
    changeSetM += "6" -> java.util.Calendar.getInstance.getTime

    // insert all into Mongo
    MongoStorageBackend.insertMapStorageEntriesFor("U-M1", changeSetM.toList)
    assertEquals(
      6,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    // individual insert api
    MongoStorageBackend.insertMapStorageEntryFor("U-M1", "7", "akka")
    MongoStorageBackend.insertMapStorageEntryFor("U-M1", "8", List(23, 25))
    assertEquals(
      8,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    // add the same changeSet for another transaction
    MongoStorageBackend.insertMapStorageEntriesFor("U-M2", changeSetM.toList)
    assertEquals(
      6,
      MongoStorageBackend.getMapStorageSizeFor("U-M2"))

    // the first transaction should remain the same
    assertEquals(
      8,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))
    changeSetM.clear
  }

  @Test
  def testMapContents = {
    fillMap
    MongoStorageBackend.insertMapStorageEntriesFor("U-M1", changeSetM.toList)
    MongoStorageBackend.getMapStorageEntryFor("U-M1", "2") match {
      case Some(x) => {
        val JsString(str) = x.asInstanceOf[JsValue]
        assertEquals("peter", str)
      }
      case None => fail("should fetch peter")
    }
    MongoStorageBackend.getMapStorageEntryFor("U-M1", "4") match {
      case Some(x) => {
        val num_list = list ! num
        val num_list(l0) = x.asInstanceOf[JsValue]
        assertEquals(3, l0.size)
      }
      case None => fail("should fetch list")
    }
    MongoStorageBackend.getMapStorageEntryFor("U-M1", "3") match {
      case Some(x) => {
        val num_list = list ! num
        val num_list(l0) = x.asInstanceOf[JsValue]
        assertEquals(2, l0.size)
      }
      case None => fail("should fetch list")
    }

    // get the entire map
    val l: List[Tuple2[AnyRef, AnyRef]] = 
      MongoStorageBackend.getMapStorageFor("U-M1")

    assertEquals(4, l.size)
    assertTrue(l.map(_._1).contains("1"))
    assertTrue(l.map(_._1).contains("2"))
    assertTrue(l.map(_._1).contains("3"))
    assertTrue(l.map(_._1).contains("4"))

    val JsString(str) = l.filter(_._1 == "2").head._2
    assertEquals(str, "peter")

    // trying to fetch for a non-existent transaction will throw
    try {
      MongoStorageBackend.getMapStorageFor("U-M2")
      fail("should throw an exception")
    } catch {case e: NoSuchElementException => {}}

    changeSetM.clear
  }

  @Test
  def testMapContentsByRange = {
    fillMap
    changeSetM += "5" -> Map(1 -> "dg", 2 -> "mc")
    MongoStorageBackend.insertMapStorageEntriesFor("U-M1", changeSetM.toList)

    // specify start and count
    val l: List[Tuple2[AnyRef, AnyRef]] = 
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", Some(Integer.valueOf(2)), None, 3)

    assertEquals(3, l.size)
    assertEquals("3", l(0)._1.asInstanceOf[String])
    val lst = l(0)._2.asInstanceOf[JsValue]
    val num_list = list ! num
    val num_list(l0) = lst
    assertEquals(List(100, 200), l0)
    assertEquals("4", l(1)._1.asInstanceOf[String])
    val ls = l(1)._2.asInstanceOf[JsValue]
    val num_list(l1) = ls
    assertEquals(List(10, 20, 30), l1)
    
    // specify start, finish and count where finish - start == count
    assertEquals(3,
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", Some(Integer.valueOf(2)), Some(Integer.valueOf(5)), 3).size)

    // specify start, finish and count where finish - start > count
    assertEquals(3,
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", Some(Integer.valueOf(2)), Some(Integer.valueOf(9)), 3).size)

    // do not specify start or finish 
    assertEquals(3,
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", None, None, 3).size)

    // specify finish and count 
    assertEquals(3,
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", None, Some(Integer.valueOf(3)), 3).size)

    // specify start, finish and count where finish < start
    assertEquals(3,
      MongoStorageBackend.getMapStorageRangeFor(
        "U-M1", Some(Integer.valueOf(2)), Some(Integer.valueOf(1)), 3).size)

    changeSetM.clear
  }

  @Test
  def testMapStorageRemove = {
    fillMap
    changeSetM += "5" -> Map(1 -> "dg", 2 -> "mc")

    MongoStorageBackend.insertMapStorageEntriesFor("U-M1", changeSetM.toList)
    assertEquals(5,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    // remove key "3"
    MongoStorageBackend.removeMapStorageFor("U-M1", "3")
    assertEquals(4,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    try {
      MongoStorageBackend.getMapStorageEntryFor("U-M1", "3")
      fail("should throw exception")
    } catch { case e => {}}

    // remove key "4"
    MongoStorageBackend.removeMapStorageFor("U-M1", "4")
    assertEquals(3,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    // remove key "2"
    MongoStorageBackend.removeMapStorageFor("U-M1", "2")
    assertEquals(2,
      MongoStorageBackend.getMapStorageSizeFor("U-M1"))

    // remove the whole stuff
    MongoStorageBackend.removeMapStorageFor("U-M1")

    try {
      MongoStorageBackend.getMapStorageFor("U-M1")
      fail("should throw exception")
    } catch { case e: NoSuchElementException => {}}

    changeSetM.clear
  }

  private def fillMap = {
    changeSetM += "1" -> "john"
    changeSetM += "2" -> "peter"
    changeSetM += "3" -> List(100, 200)
    changeSetM += "4" -> List(10, 20, 30)
    changeSetM
  }

  @Test
  def testRefStorage = {
    MongoStorageBackend.getRefStorageFor("U-R1") match {
      case None =>
      case Some(o) => fail("should be None")
    }

    val m = Map("1"->1, "2"->4, "3"->9)
    MongoStorageBackend.insertRefStorageFor("U-R1", m)
    MongoStorageBackend.getRefStorageFor("U-R1") match {
      case None => fail("should not be empty")
      case Some(r) => {
        val a = r.asInstanceOf[JsValue]
        val m1 = Symbol("1") ? num
        val m2 = Symbol("2") ? num
        val m3 = Symbol("3") ? num

        val m1(n1) = a
        val m2(n2) = a
        val m3(n3) = a

        assertEquals(n1, 1)
        assertEquals(n2, 4)
        assertEquals(n3, 9)
      }
    }

    // insert another one
    // the previous one should be replaced
    val b = List("100", "jonas")
    MongoStorageBackend.insertRefStorageFor("U-R1", b)
    MongoStorageBackend.getRefStorageFor("U-R1") match {
      case None => fail("should not be empty")
      case Some(r) => {
        val a = r.asInstanceOf[JsValue]
        val str_lst = list ! str
        val str_lst(l) = a
        assertEquals(b, l)
      }
    }
  }
}
