package akka.persistence.redis

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import akka.actor.{Actor, ActorRef}
import Actor._
import akka.stm._

/**
 * A persistent actor based on Redis sortedset storage.
 * <p/>
 * Needs a running Redis server.
 * @author <a href="http://debasishg.blogspot.com">Debasish Ghosh</a>
 */

case class AddEmail(email: String, value: String)
case class GetAll(email: String)

class MySortedSet extends Actor {

  def receive = { case message => atomic { atomicReceive(message) } }

  def atomicReceive: Receive = {
    case AddEmail(userEmail, value) => {
      val registryId = "userValues:%s".format(userEmail)
      val storageSet = RedisStorage.getSortedSet(registryId)
      storageSet.add(value.getBytes, System.currentTimeMillis.toFloat)
      self.reply(storageSet.size)
    }
    case GetAll(userEmail) => {
      val registryId = "userValues:%s".format(userEmail)
      val storageSet = RedisStorage.getSortedSet(registryId)
      self.reply(storageSet.zrange(0, -1))
    }
  }
}

import RedisStorageBackend._

@RunWith(classOf[JUnitRunner])
class RedisTicket513Spec extends
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

  describe("insert into user specific set") {
    val a = actorOf[MySortedSet]
    a.start
    it("should work with same score value") {
      (a !! AddEmail("test.user@gmail.com", "foo")).get should equal(1)
      (a !! AddEmail("test.user@gmail.com", "bar")).get should equal(2)
      (a !! GetAll("test.user@gmail.com")).get.asInstanceOf[List[_]].size should equal(2)
    }
  }
}
