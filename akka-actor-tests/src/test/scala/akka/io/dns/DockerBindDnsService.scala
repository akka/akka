/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import collection.JavaConverters._
import akka.testkit.{ AkkaSpec, SocketUtil }
import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient.LogsParam
import com.spotify.docker.client.messages.{ ContainerConfig, HostConfig, PortBinding, Volume }
import org.scalatest.concurrent.Eventually

trait DockerBindDnsService extends Eventually { self: AkkaSpec ⇒
  val client = DefaultDockerClient.fromEnv().build()

  val hostPort: Int

  var id: Option[String] = None

  override def atStartup(): Unit = {
    self.atStartup()

    val image = "sameersbn/bind:9.11.3-20180713"
    client.pull(image)

    val containerConfig = ContainerConfig.builder()
      .image(image)
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

    val creation = client.createContainer(containerConfig, "akka-test-dns-" + getClass.getCanonicalName)
    creation.warnings() should be(null)
    id = Some(creation.id())

    client.startContainer(creation.id())

    eventually {
      client.logs(creation.id(), LogsParam.stderr()).readFully() should include("all zones loaded")
    }
  }

  override def afterTermination(): Unit = {
    self.afterTermination()
    id.foreach(client.killContainer)
    id.foreach(client.removeContainer)
  }
}
