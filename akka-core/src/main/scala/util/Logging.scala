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
 *
 * The logger uses String.format:
 * http://download-llnw.oracle.com/javase/6/docs/api/java/lang/String.html#format(java.lang.String,%20java.lang.Object...)
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
  def trace(t: Throwable, fmt: => String, arg: Any, argN: Any*){
    trace(t,message(fmt,arg,argN:_*))
  }

  def trace(t: Throwable, msg: => String){
    if(trace_?) logger.trace(msg,t)
  }

  def trace(fmt: => String, arg: Any, argN: Any*){
     trace(message(fmt,arg,argN:_*))
  }

  def trace(msg: => String){
     if(trace_?) logger trace msg
  }
  //Debug
  def debug(t: Throwable, fmt: => String, arg: Any, argN: Any*){
    debug(t,message(fmt,arg,argN:_*))
  }

  def debug(t: Throwable, msg: => String){
    if(debug_?) logger.debug(msg,t)
  }

  def debug(fmt: => String, arg: Any, argN: Any*){
     debug(message(fmt,arg,argN:_*))
  }

  def debug(msg: => String){
     if(debug_?) logger debug msg
  }
  //Info
  def info(t: Throwable, fmt: => String, arg: Any, argN: Any*){
    info(t,message(fmt,arg,argN:_*))
  }

  def info(t: Throwable, msg: => String){
    if(info_?) logger.info(msg,t)
  }

  def info(fmt: => String, arg: Any, argN: Any*){
     info(message(fmt,arg,argN:_*))
  }

  def info(msg: => String){
     if(info_?) logger info msg
  }
  //Warning
  def warning(t: Throwable, fmt: => String, arg: Any, argN: Any*){
    warning(t,message(fmt,arg,argN:_*))
  }

  def warning(t: Throwable, msg: => String){
    if(warning_?) logger.warn(msg,t)
  }

  def warning(fmt: => String, arg: Any, argN: Any*){
     warning(message(fmt,arg,argN:_*))
  }

  def warning(msg: => String){
     if(warning_?) logger warn msg
  }
  //Error
  def error(t: Throwable, fmt: => String, arg: Any, argN: Any*){
    error(t,message(fmt,arg,argN:_*))
  }

  def error(t: Throwable, msg: => String){
    if(error_?) logger.error(msg,t)
  }

  def error(fmt: => String, arg: Any, argN: Any*){
     error(message(fmt,arg,argN:_*))
  }

  def error(msg: => String){
     if(error_?) logger error msg
  }

  protected def message(fmt: String, arg: Any, argN: Any*) : String = {
    if((argN eq null) || argN.isEmpty)
      fmt.format(arg)
    else
      fmt.format((arg +: argN):_*)
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
 *
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
