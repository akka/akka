package se.scalablesolutions.akka.state

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.serialization.Serializable

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
    
    it("should enter a custom object for transaction T-1") {
      val n = Name(100, "debasish", "kolkata")
      insertMapStorageEntryFor("T-1", "debasish.identity".getBytes, n.toBytes)
      getMapStorageSizeFor("T-1") should equal(5)
    }
    
    it("should enter key/values for another transaction T-2") {
      insertMapStorageEntryFor("T-2", "debasish.age".getBytes, "49".getBytes)
      insertMapStorageEntryFor("T-2", "debasish.spouse".getBytes, "paramita".getBytes)
      getMapStorageSizeFor("T-1") should equal(5)
      getMapStorageSizeFor("T-2") should equal(2)
    }
    
    it("should remove map storage for T-1 and T2") {
      removeMapStorageFor("T-1")
      removeMapStorageFor("T-2")
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
  
  describe("Store and query in vectors") {
    it("should write 4 entries in a vector for transaction T-3") {
      insertVectorStorageEntryFor("T-3", "debasish".getBytes)
      insertVectorStorageEntryFor("T-3", "maulindu".getBytes)
      val n = Name(100, "debasish", "kolkata")
      insertVectorStorageEntryFor("T-3", n.toBytes)
      insertVectorStorageEntryFor("T-3", "1200".getBytes)
      getVectorStorageSizeFor("T-3") should equal(4)
    }
  }
  
  describe("Store and query in ref") {
    it("should write 4 entries in 4 refs for transaction T-4") {
      insertRefStorageFor("T-4", "debasish".getBytes)
      insertRefStorageFor("T-4", "maulindu".getBytes)
      
      insertRefStorageFor("T-4", "1200".getBytes)
      new String(getRefStorageFor("T-4").get) should equal("1200")
      
      val n = Name(100, "debasish", "kolkata")
      insertRefStorageFor("T-4", n.toBytes)
      n.fromBytes(getRefStorageFor("T-4").get) should equal(n)
    }
  }
}

case class Name(id: Int, name: String, address: String) 
  extends Serializable.SBinary[Name] {
  import sbinary.DefaultProtocol._

  def this() = this(0, null, null)

  implicit object NameFormat extends Format[Name] {
    def reads(in : Input) = Name(
      read[Int](in),
      read[String](in),
      read[String](in))
    def writes(out: Output, value: Name) = {
      write[Int](out, value.id)
      write[String](out, value.name)
      write[String](out, value.address)
    }
  }

  def fromBytes(bytes: Array[Byte]) = fromByteArray[Name](bytes)

  def toBytes: Array[Byte] = toByteArray(this)
}
