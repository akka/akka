package akka.actor.typed.testkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.event.LoggingEvent;

public class Sl4jLoggingEvent implements LoggingEvent {

    Level level;
    Marker marker;
    String loggerName;
    Logger logger;
    String threadName;
    String message;
    Object[] argArray;
    long timeStamp;
    Throwable throwable;

    public Sl4jLoggingEvent(Level level, String msg, String loggerName) {
        setLevel(level);
        setMessage(msg);
        setLoggerName(loggerName);
    }

    public Sl4jLoggingEvent(ch.qos.logback.classic.Level level, String msg, String loggerName, long timeStamp, Object[] argArray) {
        setLevel(toLogbackLevel(level));
        setArgumentArray(argArray);
        setMessage(msg);
        setLoggerName(loggerName);
        setTimeStamp(timeStamp);
    }

    public Level toLogbackLevel(ch.qos.logback.classic.Level level) {
        switch (level.levelInt) {
            case ch.qos.logback.classic.Level.TRACE_INT:
                return Level.TRACE;
            case ch.qos.logback.classic.Level.DEBUG_INT:
                return Level.DEBUG;
            case ch.qos.logback.classic.Level.INFO_INT:
                return Level.INFO;
            case ch.qos.logback.classic.Level.WARN_INT:
                return Level.WARN;
            case ch.qos.logback.classic.Level.ERROR_INT:
                return Level.ERROR;
            default:
                throw new IllegalStateException("Level " + level.levelStr + ", " + level.levelInt + " is unknown.");

        }
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public Marker getMarker() {
        return marker;
    }

    public void setMarker(Marker marker) {
        this.marker = marker;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public void setLoggerName(String loggerName) {
        this.loggerName = loggerName;
        setLogger(LoggerFactory.getLogger(loggerName));
    }

    public Logger getLogger() {
        return logger;
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        if(getArgumentArray() != null) {
            for (int i = 0; i < getArgumentArray().length; i++) {
                message = message.replaceFirst("\\{\\}", getArgumentArray()[i].toString());
            }
        }
        this.message = message;
    }

    public Object[] getArgumentArray() {
        return argArray;
    }

    public void setArgumentArray(Object[] argArray) {
        this.argArray = argArray;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public String getThreadName() {
        return threadName;
    }

    public void setThreadName(String threadName) {
        this.threadName = threadName;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
    }
}
