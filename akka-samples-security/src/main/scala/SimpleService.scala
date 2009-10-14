/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package sample.secure

import _root_.se.scalablesolutions.akka.state.{TransactionalState,PersistentState, CassandraStorageConfig}
import _root_.se.scalablesolutions.akka.actor.{SupervisorFactory, Actor}
import _root_.se.scalablesolutions.akka.config.ScalaConfig._
import _root_.se.scalablesolutions.akka.util.Logging
import _root_.se.scalablesolutions.akka.security.{DigestAuthenticationActor, UserInfo}
import _root_.javax.annotation.security.{DenyAll,PermitAll,RolesAllowed}
import javax.ws.rs.{GET, POST, Path, Produces, Consumes}
import java.lang.Integer

class Boot {
  object factory extends SupervisorFactory {
    override def getSupervisorConfig: SupervisorConfig = {
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
        Supervise(
          new SimpleAuthenticationService,
          LifeCycle(Permanent, 100)) ::
        Supervise(
          new SecureService,
          LifeCycle(Permanent, 100)):: Nil)
    }
  }

  val supervisor = factory.newSupervisor
  supervisor.startSupervisor
}

/*
 * In akka.conf you can set the FQN of any AuthenticationActor of your wish, under the property name: akka.rest.authenticator
 */
class SimpleAuthenticationService extends DigestAuthenticationActor
{
    //If you want to have a distributed nonce-map, you can use something like below,
    //don't forget to configure your standalone Cassandra instance
    //
    //makeTransactionRequired
    //override def mkNonceMap = PersistentState.newMap(CassandraStorageConfig()).asInstanceOf[scala.collection.mutable.Map[String,Long]]

    //Use an in-memory nonce-map as default
    override def mkNonceMap = new scala.collection.mutable.HashMap[String,Long]
    //Change this to whatever you want
    override def realm = "test"

    //Dummy method that allows you to log on with whatever username with the password "bar"
    override def userInfo(username : String) : Option[UserInfo] = Some(UserInfo(username,"bar","ninja" :: "chef" :: Nil))
}

/**
 * This is merely a secured version of the scala-sample
 *
 * The interesting part is
 *  @RolesAllowed
 *  @PermitAll
 *  @DenyAll
 */

@Path("/securecount")
class SecureService extends Actor with Logging {
  makeTransactionRequired

  case object Tick
  private val KEY = "COUNTER";
  private var hasStartedTicking = false;
  private val storage = PersistentState.newMap(CassandraStorageConfig())

  @GET
  @Produces(Array("text/html"))
  @RolesAllowed(Array("chef"))
  def count = (this !! Tick).getOrElse(<error>Error in counter</error>)

  override def receive: PartialFunction[Any, Unit] = {
    case Tick => if (hasStartedTicking) {
      val counter = storage.get(KEY).get.asInstanceOf[Integer].intValue
      storage.put(KEY, new Integer(counter + 1))
      reply(<success>Tick:{counter + 1}</success>)
    } else {
      storage.put(KEY, new Integer(0))
      hasStartedTicking = true
      reply(<success>Tick: 0</success>)
    }
  }
}