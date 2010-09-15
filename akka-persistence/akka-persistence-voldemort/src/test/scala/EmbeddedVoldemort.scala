package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.matchers.ShouldMatchers
import voldemort.server.{VoldemortServer, VoldemortConfig}
import org.scalatest.{Suite, BeforeAndAfterAll, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import voldemort.utils.Utils
import java.io.File
import se.scalablesolutions.akka.util.{Logging, UUID}

@RunWith(classOf[JUnitRunner])
trait EmbeddedVoldemort extends BeforeAndAfterAll with Logging {
  this: Suite =>
  var server: VoldemortServer = null

  override protected def beforeAll(): Unit = {
    
    try {
      val dir = "./akka-persistence/akka-persistence-voldemort/target/scala_2.8.0/test-resources"
      val home = new File(dir)
      log.info("Creating Voldemort Config")
      val config = VoldemortConfig.loadFromVoldemortHome(home.getCanonicalPath)
      log.info("Starting Voldemort")
      server = new VoldemortServer(config)
      server.start
      log.info("Started")
    } catch {
      case e => log.error(e, "Error Starting Voldemort")
      throw e
    }
  }

  override protected def afterAll(): Unit = {
    server.stop
  }
}