/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;

import java.util.Optional;

/**
 * Immutable, fast and efficient Date + Time implementation without any dependencies.
 * Does not support TimeZones, all DateTime values are always GMT based.
 * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
 */
public abstract class DateTime {
    /**
     * Returns the year of this instant in GMT.
     */
    public abstract int year();

    /**
     * Returns the month of this instant in GMT.
     */
    public abstract int month();

    /**
     * Returns the day of this instant in GMT.
     */
    public abstract int day();

    /**
     * Returns the hour of this instant in GMT.
     */
    public abstract int hour();

    /**
     * Returns the minute of this instant in GMT.
     */
    public abstract int minute();

    /**
     * Returns the second of this instant in GMT.
     */
    public abstract int second();

    /**
     * Returns the weekday of this instant in GMT. Sunday is 0, Monday is 1, etc.
     */
    public abstract int weekday();

    /**
     * Returns this instant as "clicks", i.e. as milliseconds since January 1, 1970, 00:00:00 GMT
     */
    public abstract long clicks();

    /**
     * Returns if this instant interpreted as a Date in GMT belongs to a leap year.
     */
    public abstract boolean isLeapYear();

    /**
     * Returns the day of the week as a 3 letter abbreviation:
     * `Sun`, `Mon`, `Tue`, `Wed`, `Thu`, `Fri` or `Sat`
     */
    public abstract String weekdayStr();

    /**
     * Returns the month as a 3 letter abbreviation:
     * `Jan`, `Feb`, `Mar`, `Apr`, `May`, `Jun`, `Jul`, `Aug`, `Sep`, `Oct`, `Nov` or `Dec`
     */
    public abstract String monthStr();

    /**
     * Returns a String representation like this: `yyyy-mm-dd`
     */
    public abstract String toIsoDateString();

    /**
     * Returns a String representation like this: `yyyy-mm-ddThh:mm:ss`
     */
    public abstract String toIsoDateTimeString();

    /**
     * Returns a String representation like this: `yyyy-mm-dd hh:mm:ss`
     */
    public abstract String toIsoLikeDateTimeString();

    /**
     * Returns an RFC1123 date string, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`
     */
    public abstract String toRfc1123DateTimeString();

    /**
     * Returns a new DateTime instance representing the current instant.
     */
    public static DateTime now() {
        return akka.http.scaladsl.model.DateTime.now();
    }

    /**
     * Creates a new `DateTime` that represents the point in time the given number of ms earlier.
     */
    public abstract DateTime minus(long millis);

    /**
     * Creates a new `DateTime` that represents the point in time the given number of ms later.
     */
    public abstract DateTime plus(long millis);

    /**
     * Returns a new DateTime instance parsed from IsoDateTimeString as Some(dateTime). Returns None if
     * parsing has failed.
     */
    public static Optional<DateTime> fromIsoDateTimeString(String isoDateTimeString) {
        return Util.<DateTime, akka.http.scaladsl.model.DateTime>convertOption(akka.http.scaladsl.model.DateTime.fromIsoDateTimeString(isoDateTimeString));
    }

    /**
     * Returns a new DateTime instance representing the instant as defined by "clicks"
     * i.e. from the number of milliseconds since the start of "the epoch", namely
     * January 1, 1970, 00:00:00 GMT.
     * Note that this implementation discards milliseconds (i.e. rounds down to full seconds).
     */
    public static DateTime create(long clicks) {
        return akka.http.scaladsl.model.DateTime.apply(clicks);
    }

    /**
     * Returns a new DateTime instance by interpreting the given date/time components as an instant in GMT.
     */
    public static DateTime create(int year, int month, int day, int hour, int minute, int second) {
        return akka.http.scaladsl.model.DateTime.apply(year, month, day, hour, minute, second);
    }
}
