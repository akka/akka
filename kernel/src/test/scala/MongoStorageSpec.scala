package se.scalablesolutions.akka.kernel.state

import junit.framework.TestCase

import org.junit.{Test, Before}
import org.junit.Assert._

class MongoStorageSpec extends TestCase {

  val changeSet = new scala.collection.mutable.ArrayBuffer[AnyRef]

  override def setUp = {
    MongoStorage.coll.drop
  }

  @Test
  def testVectorInsertForTransactionId = {
    changeSet += "debasish"   // string
    changeSet += List(1, 2, 3) // Scala List
    val l = new java.util.ArrayList[Int]  // Java List
    l.add(100)
    l.add(200)
    changeSet += l
    MongoStorage.insertVectorStorageEntriesFor("U-A1", changeSet.toList)
    assertEquals(
      3,
      MongoStorage.getVectorStorageSizeFor("U-A1"))
    changeSet.clear

    // changeSet should be reinitialized
    changeSet += List(12, 23, 45)
    changeSet += "maulindu"
    MongoStorage.insertVectorStorageEntriesFor("U-A1", changeSet.toList)
    assertEquals(
      5,
      MongoStorage.getVectorStorageSizeFor("U-A1"))

    // add more to the same changeSet
    changeSet += "ramanendu"
    changeSet += Map(1 -> "dg", 2 -> "mc")

    // add for a diff transaction
    MongoStorage.insertVectorStorageEntriesFor("U-A2", changeSet.toList)
    assertEquals(
      4,
      MongoStorage.getVectorStorageSizeFor("U-A2"))

    // previous transaction change set should remain same
    assertEquals(
      5,
      MongoStorage.getVectorStorageSizeFor("U-A1"))

    // test single element entry
    MongoStorage.insertVectorStorageEntryFor("U-A1", Map(1->1, 2->4, 3->9))
    assertEquals(
      6,
      MongoStorage.getVectorStorageSizeFor("U-A1"))
  }

  @Test
  def testVectorFetchForKeys = {

    // initially everything 0
    assertEquals(
      0,
      MongoStorage.getVectorStorageSizeFor("U-A2"))

    assertEquals(
      0,
      MongoStorage.getVectorStorageSizeFor("U-A1"))

    // get some stuff
    changeSet += "debasish"
    changeSet += List(12, 13, 14)
    MongoStorage.insertVectorStorageEntriesFor("U-A1", changeSet.toList)

    assertEquals(
      2,
      MongoStorage.getVectorStorageSizeFor("U-A1"))

    assertEquals(
      "debasish",
      MongoStorage.getVectorStorageEntryFor("U-A1", 0).asInstanceOf[String])

    assertEquals(
      List(12, 13, 14),
      MongoStorage.getVectorStorageEntryFor("U-A1", 1).asInstanceOf[List[Int]])

    changeSet.clear
    changeSet += Map(1->1, 2->4, 3->9)
    changeSet += BigInt(2310)
    val l = new java.util.ArrayList[Int]
    l.add(100)
    l.add(200)
    l.add(300)
    changeSet += l
    MongoStorage.insertVectorStorageEntriesFor("U-A1", changeSet.toList)

    assertEquals(
      5,
      MongoStorage.getVectorStorageSizeFor("U-A1"))

    val r =
      MongoStorage.getVectorStorageRangeFor("U-A1", Some(1), None, 3)

    assertEquals(3, r.size)
    assertEquals(List(12, 13, 14), r(0).asInstanceOf[List[Int]])
  }

  @Test
  def testVectorFetchForNonExistentKeys = {
    try {
      MongoStorage.getVectorStorageEntryFor("U-A1", 1)
      fail("should throw an exception")
    } catch {case e: Predef.NoSuchElementException => {}}

    try {
      MongoStorage.getVectorStorageRangeFor("U-A1", Some(2), None, 12)
      fail("should throw an exception")
    } catch {case e: Predef.NoSuchElementException => {}}
  }
}
