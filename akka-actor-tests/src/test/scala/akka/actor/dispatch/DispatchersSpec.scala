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

object DispatchersSpec {
  val config = """
    myapp {
      mydispatcher {
        throughput = 17
      }
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatchersSpec extends AkkaSpec(DispatchersSpec.config) {

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

  lazy val allDispatchers: Map[String, Option[MessageDispatcher]] = {
    validTypes.map(t ⇒ (t, from(ConfigFactory.parseMap(Map(tipe -> t).asJava).withFallback(defaultDispatcherConfig)))).toMap
  }

  "Dispatchers" must {

    "use default dispatcher if type is missing" in {
      val dispatcher = from(ConfigFactory.empty.withFallback(defaultDispatcherConfig))
      dispatcher.map(_.name) must be(Some("DefaultDispatcher"))
    }

    "use defined properties" in {
      val dispatcher = from(ConfigFactory.parseMap(Map("throughput" -> 17).asJava).withFallback(defaultDispatcherConfig))
      dispatcher.map(_.throughput) must be(Some(17))
    }

    "use defined properties when newFromConfig" in {
      val dispatcher = newFromConfig("myapp.mydispatcher")
      dispatcher.throughput must be(17)
    }

    "use specific name when newFromConfig" in {
      val dispatcher = newFromConfig("myapp.mydispatcher")
      dispatcher.name must be("mydispatcher")
    }

    "use default dispatcher when not configured" in {
      val dispatcher = newFromConfig("myapp.other-dispatcher")
      dispatcher must be === defaultGlobalDispatcher
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

    "provide lookup of dispatchers by key" in {
      val d1 = lookup("myapp.mydispatcher")
      val d2 = lookup("myapp.mydispatcher")
      d1 must be === d2
      d1.name must be("mydispatcher")
    }

  }

}
