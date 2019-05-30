/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

import akka.actor.{ ActorSystem, Deploy, TypedActor, TypedProps }
import akka.testkit.AkkaSpec
import akka.util.IgnoreForScala212
import TypedActorRemoteDeploySpec._

import com.typesafe.config._
import com.github.ghik.silencer.silent

object TypedActorRemoteDeploySpec {
  val conf = ConfigFactory.parseString("""
      akka.actor.provider = remote
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      """)

  trait RemoteNameService {
    def getName: Future[String]
    def getNameSelfDeref: Future[String]
  }

  class RemoteNameServiceImpl extends RemoteNameService {
    @silent
    def getName: Future[String] = Future.successful(TypedActor.context.system.name)

    @silent
    def getNameSelfDeref: Future[String] = TypedActor.self[RemoteNameService].getName
  }

}

class TypedActorRemoteDeploySpec extends AkkaSpec(conf) {
  val remoteName = "remote-sys"
  val remoteSystem = ActorSystem(remoteName, conf)
  val remoteAddress = RARP(remoteSystem).provider.getDefaultAddress

  @silent
  def verify[T](f: RemoteNameService => Future[T], expected: T) = {
    val ts = TypedActor(system)
    val echoService: RemoteNameService =
      ts.typedActorOf(TypedProps[RemoteNameServiceImpl].withDeploy(Deploy(scope = RemoteScope(remoteAddress))))
    Await.result(f(echoService), 3.seconds) should ===(expected)
    val actor = ts.getActorRefFor(echoService)
    system.stop(actor)
    watch(actor)
    expectTerminated(actor)
  }

  "Typed actors" must {

    "be possible to deploy remotely and communicate with" taggedAs IgnoreForScala212 in {
      verify({ _.getName }, remoteName)
    }

    "be possible to deploy remotely and be able to dereference self" taggedAs IgnoreForScala212 in {
      verify({ _.getNameSelfDeref }, remoteName)
    }

  }

  override def afterTermination(): Unit = {
    shutdown(remoteSystem)
  }

}
