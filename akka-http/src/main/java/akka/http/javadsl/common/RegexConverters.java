/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.common;

import java.util.regex.Pattern;

import scala.collection.Seq;
import scala.collection.immutable.VectorBuilder;
import scala.util.matching.Regex;

public final class RegexConverters {
    private static final Seq<String> empty = new VectorBuilder<String>().result();
    
    /**
     * Converts the given Java Pattern into a scala Regex, without recompiling it.
     */
    public static Regex toScala(Pattern p) {
        return new Regex(p, empty);
    }
}
