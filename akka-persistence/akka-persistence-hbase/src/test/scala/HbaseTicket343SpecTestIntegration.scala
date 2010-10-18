package se.scalablesolutions.akka.persistence.hbase

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.{OneForOneStrategy,Permanent}
import Actor._
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.util.Logging

import HbaseStorageBackend._

case class GET(k: String)
case class SET(k: String, v: String)
case class REM(k: String)
case class CONTAINS(k: String)
case object MAP_SIZE
case class MSET(kvs: List[(String, String)])
case class REMOVE_AFTER_PUT(kvsToAdd: List[(String, String)], ksToRem: List[String])
case class CLEAR_AFTER_PUT(kvsToAdd: List[(String, String)])
case class PUT_WITH_SLICE(kvsToAdd: List[(String, String)], start: String, cnt: Int)
case class PUT_REM_WITH_SLICE(kvsToAdd: List[(String, String)], ksToRem: List[String], start: String, cnt: Int)

case class VADD(v: String)
case class VUPD(i: Int, v: String)
case class VUPD_AND_ABORT(i: Int, v: String)
case class VGET(i: Int)
case object VSIZE
case class VGET_AFTER_VADD(vsToAdd: List[String], isToFetch: List[Int])
case class VADD_WITH_SLICE(vsToAdd: List[String], start: Int, cnt: Int)

object Storage {
  class HbaseSampleMapStorage extends Actor {
    self.lifeCycle = Permanent
    val FOO_MAP = "akka.sample.map"

    private var fooMap = atomic { HbaseStorage.getMap(FOO_MAP) }

    def receive = {
      case SET(k, v) =>
        atomic {
          fooMap += (k.getBytes, v.getBytes)
        }
        self.reply((k, v))

      case GET(k) =>
        val v = atomic {
          fooMap.get(k.getBytes).map(new String(_)).getOrElse(k + " Not found")
        }
        self.reply(v)

      case REM(k) =>
        val v = atomic {
          fooMap -= k.getBytes
        }
        self.reply(k)

      case CONTAINS(k) =>
        val v = atomic {
          fooMap contains k.getBytes
        }
        self.reply(v)

      case MAP_SIZE =>
        val v = atomic {
          fooMap.size
        }
        self.reply(v)

      case MSET(kvs) => atomic {
          kvs.foreach {kv => fooMap += (kv._1.getBytes, kv._2.getBytes) }
        }
        self.reply(kvs.size)

      case REMOVE_AFTER_PUT(kvs2add, ks2rem) => atomic {
        kvs2add.foreach {kv =>
          fooMap += (kv._1.getBytes, kv._2.getBytes)
        }
 
        ks2rem.foreach {k =>
          fooMap -= k.getBytes
        }}
        self.reply(fooMap.size)

      case CLEAR_AFTER_PUT(kvs2add) => atomic {
        kvs2add.foreach {kv =>
          fooMap += (kv._1.getBytes, kv._2.getBytes)
        }
        fooMap.clear
        }
        self.reply(true)

      case PUT_WITH_SLICE(kvs2add, from, cnt) => 
        val v = atomic {
          kvs2add.foreach {kv =>
            fooMap += (kv._1.getBytes, kv._2.getBytes)
          }
          fooMap.slice(Some(from.getBytes), cnt)
        }
        self.reply(v: List[(Array[Byte], Array[Byte])])

      case PUT_REM_WITH_SLICE(kvs2add, ks2rem, from, cnt) => 
        val v = atomic {
          kvs2add.foreach {kv =>
            fooMap += (kv._1.getBytes, kv._2.getBytes)
          }
          ks2rem.foreach {k =>
            fooMap -= k.getBytes
          }
          fooMap.slice(Some(from.getBytes), cnt)
        }
        self.reply(v: List[(Array[Byte], Array[Byte])])
    }
  }

  class HbaseSampleVectorStorage extends Actor {
    self.lifeCycle = Permanent
    val FOO_VECTOR = "akka.sample.vector"

    private var fooVector = atomic { HbaseStorage.getVector(FOO_VECTOR) }

    def receive = {
      case VADD(v) =>
        val size = 
          atomic {
            fooVector + v.getBytes
            fooVector length
          }
        self.reply(size)

      case VGET(index) =>
        val ind =
          atomic {
            fooVector get index
          }
        self.reply(ind)

      case VGET_AFTER_VADD(vs, is) =>
        val els =
          atomic {
            vs.foreach(fooVector + _.getBytes)
            (is.foldRight(List[Array[Byte]]())(fooVector.get(_)  :: _)).map(new String(_))
          }
        self.reply(els)

      case VUPD_AND_ABORT(index, value) =>
        val l = 
          atomic {
            fooVector.update(index, value.getBytes)
            // force fail
            fooVector get 100
          }
        self.reply(index)

      case VADD_WITH_SLICE(vs, s, c) =>
        val l =
          atomic {
            vs.foreach(fooVector + _.getBytes)
            fooVector.slice(Some(s), None, c)
          }
        self.reply(l.map(new String(_)))
    }
  }
}

import Storage._

@RunWith(classOf[JUnitRunner])
class HbaseTicket343SpecTestIntegration extends Spec with ShouldMatchers with BeforeAndAfterAll with BeforeAndAfterEach {

  import org.apache.hadoop.hbase.HBaseTestingUtility
  
  val testUtil = new HBaseTestingUtility
  
  override def beforeAll {
    testUtil.startMiniCluster
  }
  
  override def afterAll {
    testUtil.shutdownMiniCluster
  }

  override def beforeEach {
    HbaseStorageBackend.drop
  }
  
  override def afterEach {
    HbaseStorageBackend.drop
  }

  describe("Ticket 343 Issue #1") {
    it("remove after put should work within the same transaction") {
      val proc = actorOf[HbaseSampleMapStorage]
      proc.start

      (proc !! SET("debasish", "anshinsoft")).getOrElse("Set failed") should equal(("debasish", "anshinsoft"))
      (proc !! GET("debasish")).getOrElse("Get failed") should equal("anshinsoft")
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(1)

      (proc !! MSET(List(("dg", "1"), ("mc", "2"), ("nd", "3")))).getOrElse("Mset failed") should equal(3)

      (proc !! GET("dg")).getOrElse("Get failed") should equal("1")
      (proc !! GET("mc")).getOrElse("Get failed") should equal("2")
      (proc !! GET("nd")).getOrElse("Get failed") should equal("3")

      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(4)

      val add = List(("a", "1"), ("b", "2"), ("c", "3"))
      val rem = List("a", "debasish")
      (proc !! REMOVE_AFTER_PUT(add, rem)).getOrElse("REMOVE_AFTER_PUT failed") should equal(5)

      (proc !! GET("debasish")).getOrElse("debasish not found") should equal("debasish Not found")
      (proc !! GET("a")).getOrElse("a not found") should equal("a Not found")

      (proc !! GET("b")).getOrElse("b not found") should equal("2")

      (proc !! CONTAINS("b")).getOrElse("b not found") should equal(true)
      (proc !! CONTAINS("debasish")).getOrElse("debasish not found") should equal(false)
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(5)
      proc.stop
    }
  }

  describe("Ticket 343 Issue #2") {
    it("clear after put should work within the same transaction") {
      val proc = actorOf[HbaseSampleMapStorage]
      proc.start

      (proc !! SET("debasish", "anshinsoft")).getOrElse("Set failed") should equal(("debasish", "anshinsoft"))
      (proc !! GET("debasish")).getOrElse("Get failed") should equal("anshinsoft")
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(1)

      val add = List(("a", "1"), ("b", "2"), ("c", "3"))
      (proc !! CLEAR_AFTER_PUT(add)).getOrElse("CLEAR_AFTER_PUT failed") should equal(true)

      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(0)
      proc.stop
    }
  }

  describe("Ticket 343 Issue #3") {
    it("map size should change after the transaction") {
      val proc = actorOf[HbaseSampleMapStorage]
      proc.start

      (proc !! SET("debasish", "anshinsoft")).getOrElse("Set failed") should equal(("debasish", "anshinsoft"))
      (proc !! GET("debasish")).getOrElse("Get failed") should equal("anshinsoft")
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(1)

      (proc !! MSET(List(("dg", "1"), ("mc", "2"), ("nd", "3")))).getOrElse("Mset failed") should equal(3)
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(4)

      (proc !! GET("dg")).getOrElse("Get failed") should equal("1")
      (proc !! GET("mc")).getOrElse("Get failed") should equal("2")
      (proc !! GET("nd")).getOrElse("Get failed") should equal("3")
      proc.stop
    }
  }

  describe("slice test") {
    it("should pass") {
      val proc = actorOf[HbaseSampleMapStorage]
      proc.start

      (proc !! SET("debasish", "anshinsoft")).getOrElse("Set failed") should equal(("debasish", "anshinsoft"))
      (proc !! GET("debasish")).getOrElse("Get failed") should equal("anshinsoft")
      // (proc !! MAP_SIZE).getOrElse("Size failed") should equal(1)

      (proc !! MSET(List(("dg", "1"), ("mc", "2"), ("nd", "3")))).getOrElse("Mset failed") should equal(3)
      (proc !! MAP_SIZE).getOrElse("Size failed") should equal(4)

      (proc !! PUT_WITH_SLICE(List(("ec", "1"), ("tb", "2"), ("mc", "10")), "dg", 3)).get.asInstanceOf[List[(Array[Byte], Array[Byte])]].map { case (k, v) => (new String(k), new String(v)) } should equal(List(("dg", "1"), ("ec", "1"), ("mc", "10")))

      (proc !! PUT_REM_WITH_SLICE(List(("fc", "1"), ("gb", "2"), ("xy", "10")), List("tb", "fc"), "dg", 5)).get.asInstanceOf[List[(Array[Byte], Array[Byte])]].map { case (k, v) => (new String(k), new String(v)) } should equal(List(("dg", "1"), ("ec", "1"), ("gb", "2"), ("mc", "10"), ("nd", "3")))
      proc.stop
    }
  }

  describe("Ticket 343 Issue #4") {
    it("vector get should not ignore elements that were in vector before transaction") {

      val proc = actorOf[HbaseSampleVectorStorage]
      proc.start

      // add 4 elements in separate transactions
      (proc !! VADD("debasish")).getOrElse("VADD failed") should equal(1)
      (proc !! VADD("maulindu")).getOrElse("VADD failed") should equal(2)
      (proc !! VADD("ramanendu")).getOrElse("VADD failed") should equal(3)
      (proc !! VADD("nilanjan")).getOrElse("VADD failed") should equal(4)

      new String((proc !! VGET(0)).get.asInstanceOf[Array[Byte]] ) should equal("nilanjan")
      new String((proc !! VGET(1)).get.asInstanceOf[Array[Byte]] ) should equal("ramanendu")
      new String((proc !! VGET(2)).get.asInstanceOf[Array[Byte]] ) should equal("maulindu")
      new String((proc !! VGET(3)).get.asInstanceOf[Array[Byte]] ) should equal("debasish")

      // now add 3 more and do gets in the same transaction
      (proc !! VGET_AFTER_VADD(List("a", "b", "c"), List(0, 2, 4))).get.asInstanceOf[List[String]] should equal(List("c", "a", "ramanendu"))
      proc.stop
    }
  }

  describe("Ticket 343 Issue #6") {
    it("vector update should not ignore transaction") {
      val proc = actorOf[HbaseSampleVectorStorage]
      proc.start

      // add 4 elements in separate transactions
      (proc !! VADD("debasish")).getOrElse("VADD failed") should equal(1)
      (proc !! VADD("maulindu")).getOrElse("VADD failed") should equal(2)
      (proc !! VADD("ramanendu")).getOrElse("VADD failed") should equal(3)
      (proc !! VADD("nilanjan")).getOrElse("VADD failed") should equal(4)

      evaluating {
        (proc !! VUPD_AND_ABORT(0, "virat")).getOrElse("VUPD_AND_ABORT failed") 
      } should produce [Exception]

      // update aborts and hence values will remain unchanged
      new String((proc !! VGET(0)).get.asInstanceOf[Array[Byte]] ) should equal("nilanjan")
      proc.stop
    }
  }

  describe("Ticket 343 Issue #5") {
    it("vector slice() should not ignore elements added in current transaction") {
      val proc = actorOf[HbaseSampleVectorStorage]
      proc.start

      // add 4 elements in separate transactions
      (proc !! VADD("debasish")).getOrElse("VADD failed") should equal(1)
      (proc !! VADD("maulindu")).getOrElse("VADD failed") should equal(2)
      (proc !! VADD("ramanendu")).getOrElse("VADD failed") should equal(3)
      (proc !! VADD("nilanjan")).getOrElse("VADD failed") should equal(4)

      // slice with no new elements added in current transaction
      (proc !! VADD_WITH_SLICE(List(), 2, 2)).getOrElse("VADD_WITH_SLICE failed") should equal(Vector("maulindu", "debasish"))

      // slice with new elements added in current transaction
      (proc !! VADD_WITH_SLICE(List("a", "b", "c", "d"), 2, 2)).getOrElse("VADD_WITH_SLICE failed") should equal(Vector("b", "a"))
      proc.stop
    }
  }
}
