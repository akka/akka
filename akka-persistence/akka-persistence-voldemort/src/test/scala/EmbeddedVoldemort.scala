package se.scalablesolutions.akka.persistence.voldemort

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.UUID
import voldemort.server.{VoldemortServer, VoldemortConfig}
import org.scalatest.{Suite, BeforeAndAfterAll, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import voldemort.utils.Utils
import java.io.File

@RunWith(classOf[JUnitRunner])
trait EmbeddedVoldemort extends BeforeAndAfterAll {
  this: Suite =>
  var server: VoldemortServer = null

  override protected def beforeAll(): Unit = {
    
    try {
      val dir = "./akka-persistence/akka-persistence-voldemort/src/test/resources"
      val home = new File(dir)
      val config = VoldemortConfig.loadFromVoldemortHome(home.getCanonicalPath)
      server = new VoldemortServer(config)
      server.start        
    } catch {
      case e => e.printStackTrace
    }
  }

  override protected def afterAll(): Unit = {
    server.stop
  }
}