/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor._
import akka.dispatch.Dispatchers
import akka.config.ConfigurationException
import akka.util.{ ListenerManagement, ReflectiveAccess }
import akka.serialization._
import akka.AkkaException
import akka.AkkaApplication

/**
 * Event handler.
 * <p/>
 * Create, add and remove a listener:
 * <pre>
 * val eventHandlerListener = Actor.actorOf(new Actor {
 *   self.dispatcher = EventHandler.EventHandlerDispatcher
 *
 *   def receive = {
 *     case EventHandler.Error(cause, instance, message) ⇒ ...
 *     case EventHandler.Warning(instance, message)      ⇒ ...
 *     case EventHandler.Info(instance, message)         ⇒ ...
 *     case EventHandler.Debug(instance, message)        ⇒ ...
 *     case genericEvent                                 ⇒ ...
 * }
 * })
 *
 * EventHandler.addListener(eventHandlerListener)
 * ...
 * EventHandler.removeListener(eventHandlerListener)
 * </pre>
 * <p/>
 * However best is probably to register the listener in the 'akka.conf'
 * configuration file.
 * <p/>
 * Log an error event:
 * <pre>
 * EventHandler.notify(EventHandler.Error(exception, this, message))
 * </pre>
 * Or use the direct methods (better performance):
 * <pre>
 * EventHandler.error(exception, this, message)
 * </pre>
 *
 * Shut down the EventHandler:
 * <pre>
 * EventHandler.shutdown()
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
