/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.util

import org.slf4j.{Logger => SLFLogger,LoggerFactory => SLFLoggerFactory}

import java.io.StringWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Base trait for all classes that wants to be able use the logging infrastructure.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
trait Logging {
  @sjson.json.JSONProperty(ignore = true) @transient lazy val log = Logger(this.getClass.getName)
}

/**
 * Scala SLF4J wrapper
 *
 * ex.
 *
 * class Foo extends Logging {
 *   log.info("My foo is %s","alive")
 *   log.error(new Exception(),"My foo is %s","broken")
 * }
 */
class Logger(val logger: SLFLogger)
{
  def name       = logger.getName

  def trace_?    = logger.isTraceEnabled
  def debug_?    = logger.isDebugEnabled
  def info_?     = logger.isInfoEnabled
  def warning_?  = logger.isWarnEnabled
  def error_?    = logger.isErrorEnabled

  //Trace
  def trace(t: => Throwable,msg: => String,args: Any*){
    ifTrace(t,message(msg,args:_*))
  }

  def trace(msg: => String,args: Any*){
    ifTrace(message(msg,args:_*))
  }

  def ifTrace(msg: => String): Unit = if (trace_?) logger trace msg

  def ifTrace(t: => Throwable,msg: => String): Unit = {
    if(trace_?) logger.trace(msg,t)
  }

  //Debug
  def debug(t: => Throwable,msg: => String,args: Any*){
    ifDebug(t,message(msg,args:_*))
  }

  def debug(msg: => String,args: Any*){
    ifDebug(message(msg,args:_*))
  }

  def ifDebug(msg: => String): Unit = if (debug_?) logger debug msg

  def ifDebug(t: => Throwable,msg: => String): Unit = {
    if(debug_?) logger.debug(msg,t)
  }

  //Info
  def info(t: => Throwable,msg: => String,args: Any*){
    ifInfo(t,message(msg,args:_*))
  }

  def info(msg: => String,args: Any*){
    ifInfo(message(msg,args:_*))
  }

  def ifInfo(msg: => String): Unit = if (info_?) logger info msg

  def ifInfo(t: => Throwable,msg: => String): Unit = {
    if(info_?) logger.info(msg,t)
  }

  //Warning
  def warning(t: => Throwable,msg: => String,args: Any*){
    ifWarning(t,message(msg,args:_*))
  }

  def warning(msg: => String,args: Any*){
    ifWarning(message(msg,args:_*))
  }

  def ifWarning(msg: => String): Unit = if (warning_?) logger warn msg

  def ifWarning(t: => Throwable,msg: => String): Unit = {
    if(warning_?) logger.warn(msg,t)
  }

  //Error
  def error(t: => Throwable,msg: => String,args: Any*){
    ifError(t,message(msg,args:_*))
  }

  def error(msg: => String,args: Any*){
    ifError(message(msg,args:_*))
  }

  def ifError(msg: => String): Unit = if (error_?) logger error msg

  def ifError(t: => Throwable,msg: => String): Unit = {
    if(error_?) logger.error(msg,t)
  }

  protected def message(msg: String, args: Any*) : String = {
    if(args.isEmpty || (args eq null))
      msg
    else
      msg.format(args:_*)
  }
}

/**
 * Logger factory
 *
 * ex.
 *
 * val logger = Logger("my.cool.logger")
 * val logger = Logger(classOf[Banana])
 * val rootLogger = Logger.root
 */
object Logger
{
  def apply(logger: String)  : Logger = new Logger(SLFLoggerFactory getLogger logger)

  def apply(clazz: Class[_]) : Logger = apply(clazz.getName)

  def root                   : Logger = apply(SLFLogger.ROOT_LOGGER_NAME)
}



/**
 * LoggableException is a subclass of Exception and can be used as the base exception
 * for application specific exceptions.
 * <p/>
 * It keeps track of the exception is logged or not and also stores the unique id,
 * so that it can be carried all along to the client tier and displayed to the end user.
 * The end user can call up the customer support using this number.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
 // FIXME make use of LoggableException
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
        case Some(e) => log.error("Logged Exception [%s] %s", uniqueId, getStackTraceAsString(e))
        case None => log.error("Logged Exception [%s] %s", uniqueId, getStackTraceAsString(this))
      }
      isLogged = true
    }
  }

  private def getExceptionID: String = {
    val hostname: String = try {
      InetAddress.getLocalHost.getHostName
    } catch {
      case e: UnknownHostException =>
        log.error("Could not get hostname to generate loggable exception")
        "N/A"
    }
    hostname + "_" + System.currentTimeMillis
  }

  private def getStackTraceAsString(exception: Throwable): String = {
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    exception.printStackTrace(pw)
    sw.toString
  }
}
