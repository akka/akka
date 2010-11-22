package akka.persistence.voldemort

import voldemort.server.{VoldemortServer, VoldemortConfig}
import org.scalatest.{Suite, BeforeAndAfterAll}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import java.io.File
import akka.util.{Logging}
import collection.JavaConversions
import voldemort.store.memory.InMemoryStorageConfiguration
import voldemort.client.protocol.admin.{AdminClientConfig, AdminClient}


trait EmbeddedVoldemort extends BeforeAndAfterAll with Logging {
  this: Suite =>
  var server: VoldemortServer = null

  override protected def beforeAll(): Unit = {

    try {
      val dir = "./akka-persistence/akka-persistence-voldemort/target/test-resources"
      val home = new File(dir)
      log.info("Creating Voldemort Config")
      val config = VoldemortConfig.loadFromVoldemortHome(home.getCanonicalPath)
      config.setStorageConfigurations(JavaConversions.asList(List(classOf[InMemoryStorageConfiguration].getName)))
      log.info("Starting Voldemort")
      server = new VoldemortServer(config)
      server.start
      VoldemortStorageBackend.resetAccess
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
