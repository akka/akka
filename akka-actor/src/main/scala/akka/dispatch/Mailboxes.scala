/**
 *   Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import com.typesafe.config.{ ConfigFactory, Config }
import akka.actor.{ Actor, DynamicAccess, ActorSystem }
import akka.event.EventStream
import java.util.concurrent.ConcurrentHashMap
import akka.event.Logging.Warning
import akka.ConfigurationException
import scala.annotation.tailrec
import java.lang.reflect.ParameterizedType

private[akka] class Mailboxes(
  val settings: ActorSystem.Settings,
  val eventStream: EventStream,
  dynamicAccess: DynamicAccess) {

  private val mailboxTypeConfigurators = new ConcurrentHashMap[String, Option[MailboxTypeConfigurator]]

  private val mailboxBindings: Map[Class[_ <: Any], String] = {
    import scala.collection.JavaConverters._
    settings.config.getConfig("akka.actor.mailbox.requirements").root.unwrapped.asScala
      .toMap.foldLeft(Map.empty[Class[_ <: Any], String]) {
        case (m, (k, v)) ⇒
          dynamicAccess.getClassFor[Any](k).map {
            case x ⇒ m.updated(x, v.toString)
          }.recover {
            case e ⇒
              throw new ConfigurationException(s"Type [${k}] specified as akka.actor.mailbox.requirement " +
                s"[${v}] in config can't be loaded due to [${e.getMessage}]", e)
          }.get
      }
  }

  /**
   * Returns a mailbox type as specified in configuration, based on the id, or if not defined None.
   */
  def lookup(id: String): Option[MailboxType] = lookupConfigurator(id).map { _.mailboxType }

  /**
   * Returns a mailbox type as specified in configuration, based on the type, or if not defined None.
   */
  def lookupByQueueType(queueType: Class[_ <: Any]): Option[MailboxType] =
    lookupId(queueType).flatMap(x ⇒ lookup(x))

  private final val rmqClass = classOf[RequiresMessageQueue[_]]
  /**
   * Return the required message queue type for this class if any.
   */
  def getRequiredType(actorClass: Class[_ <: Actor]): Option[Class[_]] = {
    @tailrec
    def innerRequiredType(classes: Iterator[Class[_]]): Option[Class[_]] = {
      if (classes.isEmpty) None
      else {
        val c = classes.next()
        if (rmqClass.isAssignableFrom(c)) {
          val ifaces = c.getGenericInterfaces
          val tpe = ifaces.collectFirst {
            case t: ParameterizedType if rmqClass.isAssignableFrom(t.getRawType.asInstanceOf[Class[_]]) ⇒
              t.getActualTypeArguments.head.asInstanceOf[Class[_]]
          }
          if (tpe.isDefined) tpe
          else innerRequiredType(classes ++ ifaces.map {
            case c: Class[_]          ⇒ c
            case c: ParameterizedType ⇒ c.getRawType.asInstanceOf[Class[_]]
          } ++ Iterator(c.getSuperclass))
        } else {
          innerRequiredType(classes)
        }
      }
    }
    if (rmqClass.isAssignableFrom(actorClass)) innerRequiredType(Iterator(actorClass))
    else None
  }

  /**
   * Check if this class can have a required message queue type.
   */
  def hasRequiredType(actorClass: Class[_ <: Actor]): Boolean = rmqClass.isAssignableFrom(actorClass)

  private def lookupId(queueType: Class[_]): Option[String] = {
    mailboxBindings.get(queueType) match {
      case None ⇒
        eventStream.publish(Warning("Mailboxes", this.getClass, s"Mailbox Mapping for [${queueType}] not configured"))
        None
      case s ⇒ s
    }
  }

  private def lookupConfigurator(id: String): Option[MailboxTypeConfigurator] = {
    mailboxTypeConfigurators.get(id) match {
      case null ⇒
        // It doesn't matter if we create a mailbox type configurator that isn't used due to concurrent lookup.
        val newConfigurator =
          if (settings.config.hasPath(id)) {
            Some(new MailboxTypeConfigurator(settings, config(id), dynamicAccess))
          } else {
            eventStream.publish(Warning("Mailboxes", this.getClass, s"Mailbox Type [${id}] not configured"))
            None
          }

        if (newConfigurator.isDefined) {
          mailboxTypeConfigurators.putIfAbsent(id, newConfigurator) match {
            case null     ⇒ newConfigurator
            case existing ⇒ existing
          }
        } else None

      case existing ⇒ existing
    }
  }

  //INTERNAL API
  private def config(id: String): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(Map("id" -> id).asJava)
      .withFallback(settings.config.getConfig(id))
  }
}

private[akka] class MailboxTypeConfigurator(
  val settings: ActorSystem.Settings,
  val config: Config,
  dynamicAccess: DynamicAccess) {
  private val instance: MailboxType = {
    val id = config.getString("id")
    config.getString("mailbox-type") match {
      case "" ⇒ throw new IllegalArgumentException(s"The setting mailbox-type, defined in [${id}}] is empty")
      case fqcn ⇒
        val args = List(classOf[ActorSystem.Settings] -> settings, classOf[Config] -> config)
        dynamicAccess.createInstanceFor[MailboxType](fqcn, args).recover({
          case exception ⇒
            throw new IllegalArgumentException(
              (s"Cannot instantiate MailboxType [${fqcn}], defined in [${id}], make sure it has a public" +
                " constructor with [akka.actor.ActorSystem.Settings, com.typesafe.config.Config] parameters"),
              exception)
        }).get
    }
  }

  def mailboxType: MailboxType = instance
}
