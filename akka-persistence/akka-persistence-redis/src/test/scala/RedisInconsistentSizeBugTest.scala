package se.scalablesolutions.akka.persistence.redis

import sbinary._
import sbinary.Operations._
import sbinary.DefaultProtocol._

import se.scalablesolutions.akka.actor.{Actor, ActorRef}
import se.scalablesolutions.akka.config.OneForOneStrategy
import Actor._
import se.scalablesolutions.akka.persistence.common.PersistentVector
import se.scalablesolutions.akka.stm.global._
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.util.Logging

import java.util.{Calendar, Date}

object Serial {
  implicit object DateFormat extends Format[Date] {
    def reads(in : Input) = new Date(read[Long](in))
    def writes(out: Output, value: Date) = write[Long](out, value.getTime)
  }
  case class Name(id: Int, name: String, address: String, dateOfBirth: Date, dateDied: Option[Date])
  implicit val NameFormat: Format[Name] = asProduct5(Name)(Name.unapply(_).get)
}

case class GETFOO(s: String)
case class SETFOO(s: String)

object SampleStorage {
  class RedisSampleStorage extends Actor {
    self.lifeCycle = Some(LifeCycle(Permanent))
    val EVENT_MAP = "akka.sample.map"

    private var eventMap = atomic { RedisStorage.getMap(EVENT_MAP) }

    import sbinary._
    import DefaultProtocol._
    import Operations._
    import Serial._
    import java.util.Calendar

    val dtb = Calendar.getInstance.getTime
    val n = Name(100, "debasish ghosh", "kolkata", dtb, Some(dtb))

    def receive = {
      case SETFOO(str) =>
        atomic {
          eventMap += (str.getBytes, toByteArray[Name](n))
        }
        self.reply(str)

      case GETFOO(str) =>
        val ev = atomic {
          eventMap.keySet.size
        }
        println("************* " + ev)
        self.reply(ev)
    }
  }
}

import Serial._
import SampleStorage._

object Runner {
  def run {
    val proc = actorOf[RedisSampleStorage]
    proc.start
    val i = (proc !! SETFOO("debasish")).as[String]
    println("i = " + i)
    val ev = (proc !! GETFOO("debasish")).as[Int]
    println(ev)
  }
}
