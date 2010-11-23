/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.security

import akka.actor.{SupervisorFactory, Actor}
import akka.actor.Actor._
import akka.config.Supervision._
import akka.util.Logging
import akka.security.{BasicAuthenticationActor,BasicCredentials,SpnegoAuthenticationActor,DigestAuthenticationActor, UserInfo}
import akka.stm._
import akka.stm.TransactionalMap
import akka.actor.ActorRegistry.actorFor

class Boot {
  val factory = SupervisorFactory(
    SupervisorConfig(
      OneForOneStrategy(List(classOf[Exception]), 3, 100),
      // Dummy implementations of all authentication actors
      // see akka.conf to enable one of these for the AkkaSecurityFilterFactory
      Supervise(
        actorOf[BasicAuthenticationService],
        Permanent) ::

      // Supervise(
      //   actorOf[DigestAuthenticationService],
      //   Permanent) ::
      // Supervise(
      //   actorOf[SpnegoAuthenticationService],
      //   Permanent) ::

      Supervise(
        actorOf[SecureTickActor],
        Permanent):: Nil))

  val supervisor = factory.newInstance
  supervisor.start
}

/*
 * In akka.conf you can set the FQN of any AuthenticationActor of your wish, under the property name: akka.http.authenticator
 */
class DigestAuthenticationService extends DigestAuthenticationActor {
  //If you want to have a distributed nonce-map, you can use something like below,
  //don't forget to configure your standalone Cassandra instance
  //
  //makeTransactionRequired
  //override def mkNonceMap = Storage.newMap(CassandraStorageConfig()).asInstanceOf[scala.collection.mutable.Map[String,Long]]

  //Use an in-memory nonce-map as default
  override def mkNonceMap = new scala.collection.mutable.HashMap[String, Long]

  //Change this to whatever you want
  override def realm = "test"

  //Dummy method that allows you to log on with whatever username with the password "bar"
  override def userInfo(username: String): Option[UserInfo] = Some(UserInfo(username, "bar", "ninja" :: "chef" :: Nil))
}

class BasicAuthenticationService extends BasicAuthenticationActor {

  //Change this to whatever you want
  override def realm = "test"

  //Dummy method that allows you to log on with whatever username
  def verify(odc: Option[BasicCredentials]): Option[UserInfo] = odc match {
    case Some(dc) => userInfo(dc.username)
    case _ => None
  }

  //Dummy method that allows you to log on with whatever username with the password "bar"
  def userInfo(username: String): Option[UserInfo] = Some(UserInfo(username, "bar", "ninja" :: "chef" :: Nil))

}

class SpnegoAuthenticationService extends SpnegoAuthenticationActor {
  def rolesFor(user: String) = "ninja" :: "chef" :: Nil

}

/**
 * a REST Actor with class level paranoia settings to deny all access
 *
 * The interesting part is
 * @RolesAllowed
 * @PermitAll
 * @DenyAll
 */
import java.lang.Integer
import javax.annotation.security.{RolesAllowed, DenyAll, PermitAll}
import javax.ws.rs.{GET, Path, Produces}

@Path("/secureticker")
class SecureTickService {

  /**
   * allow access for any user to "/secureticker/public"
   */
  @GET
  @Produces(Array("text/xml"))
  @Path("/public")
  @PermitAll
  def publicTick = tick

  /**
   * restrict access to "/secureticker/chef" users with "chef" role
   */
  @GET
  @Path("/chef")
  @Produces(Array("text/xml"))
  @RolesAllowed(Array("chef"))
  def chefTick = tick

  /**
   * access denied for any user to default Path "/secureticker/"
   */
  @GET
  @Produces(Array("text/xml"))
  @DenyAll
  def paranoiaTick = tick

  def tick = {
        //Fetch the first actor of type PersistentSimpleServiceActor
        //Send it the "Tick" message and expect a NdeSeq back
        val result = for{a <- actorFor[SecureTickActor]
                         r <- (a !! "Tick").as[Integer]} yield r
        //Return either the resulting NodeSeq or a default one
        result match {
      case (Some(counter)) => (<success>Tick: {counter}</success>)
      case _ => (<error>Error in counter</error>)
    }
  }
}

class SecureTickActor extends Actor with Logging {
  private val KEY = "COUNTER"
  private var hasStartedTicking = false
  private val storage = TransactionalMap[String, Integer]()
  def receive = {
    case "Tick" => if (hasStartedTicking) {
      val count = atomic {
        val current = storage.get(KEY).get.intValue
        val updated = current + 1
        storage.put(KEY, updated)
        updated
      }
      self.reply(new Integer(count))
    } else {
      atomic {
        storage.put(KEY, 0)
      }
      hasStartedTicking = true
      self.reply(new Integer(0))
    }
  }
}
