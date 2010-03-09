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

  describe("atomic increment in ref") {
    it("should increment an existing key value by 1") {
      insertRefStorageFor("T-4-1", "1200".getBytes)
      new String(getRefStorageFor("T-4-1").get) should equal("1200")
      incrementAtomically("T-4-1").get should equal(1201)
    }
    it("should create and increment a non-existing key value by 1") {
      incrementAtomically("T-4-2").get should equal(1)
      new String(getRefStorageFor("T-4-2").get) should equal("1")
    }
    it("should increment an existing key value by the amount specified") {
      insertRefStorageFor("T-4-3", "1200".getBytes)
      new String(getRefStorageFor("T-4-3").get) should equal("1200")
      incrementByAtomically("T-4-3", 50).get should equal(1250)
    }
    it("should create and increment a non-existing key value by the amount specified") {
      incrementByAtomically("T-4-4", 20).get should equal(20)
      new String(getRefStorageFor("T-4-4").get) should equal("20")
    }
  }

  describe("atomic decrement in ref") {
    it("should decrement an existing key value by 1") {
      insertRefStorageFor("T-4-5", "1200".getBytes)
      new String(getRefStorageFor("T-4-5").get) should equal("1200")
      decrementAtomically("T-4-5").get should equal(1199)
    }
    it("should create and decrement a non-existing key value by 1") {
      decrementAtomically("T-4-6").get should equal(-1)
      new String(getRefStorageFor("T-4-6").get) should equal("-1")
    }
    it("should decrement an existing key value by the amount specified") {
      insertRefStorageFor("T-4-7", "1200".getBytes)
      new String(getRefStorageFor("T-4-7").get) should equal("1200")
      decrementByAtomically("T-4-7", 50).get should equal(1150)
    }
    it("should create and decrement a non-existing key value by the amount specified") {
      decrementByAtomically("T-4-8", 20).get should equal(-20)
      new String(getRefStorageFor("T-4-8").get) should equal("-20")
    }
  }

  describe("atomic increment in ref") {
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
      
      zscore("hackers", "alan turing".getBytes) should equal("1912")
      zscore("hackers", "richard stallman".getBytes) should equal("1953")
      zscore("hackers", "claude shannon".getBytes) should equal("1916")
      zscore("hackers", "linus torvalds".getBytes) should equal("1969")

      val s: List[Array[Byte]] = zrange("hackers", 0, 2)
      s.size should equal(3)
      s.map(new String(_)) should equal(List("alan turing", "claude shannon", "alan kay"))
      
      var sorted: List[String] = 
        List("alan turing", "claude shannon", "alan kay", "richard stallman", "yukihiro matsumoto", "linus torvalds")
      
      val t: List[Array[Byte]] = zrange("hackers", 0, -1)
      t.size should equal(6)
      t.map(new String(_)) should equal(sorted)
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
