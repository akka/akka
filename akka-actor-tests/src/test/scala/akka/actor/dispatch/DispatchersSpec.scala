/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.reflect.{ Manifest }
import akka.dispatch._
import akka.testkit.AkkaSpec
import scala.collection.JavaConverters._
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatchersSpec extends AkkaSpec {

  val df = system.dispatcherFactory
  import df._

  val tipe = "type"
  val keepalivems = "keep-alive-time"
  val corepoolsizefactor = "core-pool-size-factor"
  val maxpoolsizefactor = "max-pool-size-factor"
  val allowcoretimeout = "allow-core-timeout"
  val throughput = "throughput" // Throughput for Dispatcher

  def instance(dispatcher: MessageDispatcher): (MessageDispatcher) ⇒ Boolean = _ == dispatcher
  def ofType[T <: MessageDispatcher: Manifest]: (MessageDispatcher) ⇒ Boolean = _.getClass == manifest[T].erasure

  def typesAndValidators: Map[String, (MessageDispatcher) ⇒ Boolean] = Map(
    "BalancingDispatcher" -> ofType[BalancingDispatcher],
    "Dispatcher" -> ofType[Dispatcher])

  def validTypes = typesAndValidators.keys.toList

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  val dispatcherConf = ConfigFactory.parseString("""
      myapp {
        mydispatcher {
          throughput = 17
        }
      }
      """, ConfigParseOptions.defaults)

  lazy val allDispatchers: Map[String, Option[MessageDispatcher]] = {
    validTypes.map(t ⇒ (t, from(ConfigFactory.parseMap(Map(tipe -> t).asJava).withFallback(defaultDispatcherConfig)))).toMap
  }

  "Dispatchers" must {

    "use default dispatcher if type is missing" in {
      val dispatcher = from(ConfigFactory.parseMap(Map().asJava).withFallback(defaultDispatcherConfig))
      dispatcher.map(_.name) must be(Some("DefaultDispatcher"))
    }

    "use defined properties" in {
      val dispatcher = from(ConfigFactory.parseMap(Map("throughput" -> 17).asJava).withFallback(defaultDispatcherConfig))
      dispatcher.map(_.throughput) must be(Some(17))
    }

    "use defined properties when fromConfig" in {
      val dispatcher = fromConfig("myapp.mydispatcher", cfg = dispatcherConf)
      dispatcher.throughput must be(17)
    }

    "use specific name when fromConfig" in {
      val dispatcher = fromConfig("myapp.mydispatcher", cfg = dispatcherConf)
      dispatcher.name must be("mydispatcher")
    }

    "throw IllegalArgumentException if type does not exist" in {
      intercept[IllegalArgumentException] {
        from(ConfigFactory.parseMap(Map(tipe -> "typedoesntexist").asJava).withFallback(defaultDispatcherConfig))
      }
    }

    "get the correct types of dispatchers" in {
      //It can create/obtain all defined types
      assert(allDispatchers.values.forall(_.isDefined))
      //All created/obtained dispatchers are of the expeced type/instance
      assert(typesAndValidators.forall(tuple ⇒ tuple._2(allDispatchers(tuple._1).get)))
    }

  }

}
