/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import net.lag.configgy.Config
import net.lag.logging.Logger

import java.util.Date
import java.io.StringWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Base trait for all classes that wants to be able use the logging infrastructure.
 */
trait Logging {
  @transient val log = Logger.get(this.getClass.getName)
}

/**
 * LoggableException is a subclass of Exception and can be used as the base exception
 * for application specific exceptions.
 * <p/>
 * It keeps track of the exception is logged or not and also stores the unique id,
 * so that it can be carried all along to the client tier and displayed to the end user.
 * The end user can call up the customer support using this number.
 */
class LoggableException extends Exception with Logging {
  private val uniqueId = getExceptionID
  private var originalException: Option[Exception] = None
  private var isLogged = false

  def this(baseException: Exception) = {
    this()
    originalException = Some(baseException)
  }

  def logException = synchronized {
    if (!isLogged) {
      originalException match {
        case Some(e) => log.error("Logged Exception [%s] %s", uniqueId, getStackTrace(e))
	case None => log.error("Logged Exception [%s] %s", uniqueId, getStackTrace(this))
      }
      isLogged = true
    }
 }

  def getExceptionID: String = {
    val hostname: String = try {
      InetAddress.getLocalHost.getHostName
    } catch {
      case e: UnknownHostException =>
        log.error("Could not get hostname to generate loggable exception")
        "N/A"
    }
    hostname + "_" + System.currentTimeMillis
  }

  def getStackTrace(exception: Throwable): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    exception.printStackTrace(pw)
    sw.toString
  }
}
