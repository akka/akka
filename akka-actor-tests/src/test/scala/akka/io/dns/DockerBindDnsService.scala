/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import collection.JavaConverters._
import akka.testkit.AkkaSpec
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.{ ListContainersParam, LogsParam }
import com.spotify.docker.client.messages.{ ContainerConfig, HostConfig, PortBinding }
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

trait DockerBindDnsService extends Eventually { self: AkkaSpec ⇒
  val client = DefaultDockerClient.fromEnv().build()

  val hostPort: Int

  var id: Option[String] = None

  def dockerAvailable() = Try(client.ping()).isSuccess

  override def atStartup(): Unit = {
    log.info("Running on port port {}", hostPort)
    self.atStartup()

    // https://github.com/sameersbn/docker-bind/pull/61
    val image = "raboof/bind:9.11.3-20180713-nochown"
    try {
      client.pull(image)
    } catch {
      case NonFatal(_) ⇒
        log.warning(s"Failed to pull docker image [$image], is docker running?")
        return
    }

    val containerConfig = ContainerConfig.builder()
      .image(image)
      .env("NO_CHOWN=true")
      .cmd("-4") // only listen on ipv4
      .hostConfig(
        HostConfig.builder()
          .portBindings(Map(
            "53/tcp" -> List(PortBinding.of("", hostPort)).asJava,
            "53/udp" -> List(PortBinding.of("", hostPort)).asJava
          ).asJava)
          .binds(HostConfig.Bind.from(new java.io.File("akka-actor-tests/src/test/bind/").getAbsolutePath).to("/data/bind").build())
          .build()
      )
      .build()

    val containerName = "akka-test-dns-" + getClass.getCanonicalName

    client.listContainers(ListContainersParam.allContainers()).asScala
      .find(_.names().asScala.exists(_.contains(containerName))).foreach(c ⇒ {
        if ("running" == c.state()) {
          client.killContainer(c.id)
        }
        client.removeContainer(c.id)
      })

    val creation = client.createContainer(containerConfig, containerName)
    creation.warnings() should be(null)
    id = Some(creation.id())

    client.startContainer(creation.id())

    eventually(timeout(25.seconds)) {
      client.logs(creation.id(), LogsParam.stderr()).readFully() should include("all zones loaded")
    }
  }

  override def afterTermination(): Unit = {
    self.afterTermination()
    id.foreach(client.killContainer)
    id.foreach(client.removeContainer)
  }
}
