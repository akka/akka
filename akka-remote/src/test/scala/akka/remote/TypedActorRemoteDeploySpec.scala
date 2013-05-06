/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import com.typesafe.config._
import scala.concurrent.{ Await, Future }
import TypedActorRemoteDeploySpec._
import akka.actor.{ Deploy, ActorSystem, TypedProps, TypedActor }
import scala.concurrent.duration._

object TypedActorRemoteDeploySpec {
  val conf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.remote.RemoteActorRefProvider"
      akka.remote.netty.tcp.port = 0
                                                            """)

  trait RemoteNameService {
    def getName: Future[String]
  }

  class RemoteNameServiceImpl extends RemoteNameService {
    def getName: Future[String] = Future.successful(TypedActor.context.system.name)
  }

}

class TypedActorRemoteDeploySpec extends AkkaSpec(conf) {
  val remoteName = "remote-sys"
  val remoteSystem = ActorSystem(remoteName, conf)
  val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

  "Typed actors" must {

    "be possible to deploy remotely and communicate with" in {
      val echoService: RemoteNameService = TypedActor(system).typedActorOf(
        TypedProps[RemoteNameServiceImpl].withDeploy(Deploy(scope = RemoteScope(remoteAddress))))
      Await.result(echoService.getName, 3.seconds) must be === remoteName
    }

  }

  override def afterTermination() {
    remoteSystem.shutdown()
    remoteSystem.awaitTermination(5.seconds)
  }

}
