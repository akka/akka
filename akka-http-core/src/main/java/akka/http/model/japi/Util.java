/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.*;
import akka.http.util.ObjectRegistry;
import akka.japi.Option;
import org.reactivestreams.Publisher;
import scala.None;
import scala.None$;
import scala.NotImplementedError;
import scala.collection.immutable.Map$;
import scala.collection.immutable.Seq;

import java.util.Arrays;
import java.util.Map;

/**
 * Contains internal helper methods.
 */
public abstract class Util {
    @SuppressWarnings("unchecked") // no support for covariance of option in Java
    // needed to provide covariant conversions that the Java interfaces don't provide automatically.
    // The alternative would be having to cast around everywhere instead of doing it here in a central place.
    public static <U, T extends U> Option<U> convertOption(scala.Option<T> o) {
        return (Option<U>)(Option) akka.japi.Option.fromScalaOption(o);
    }
    @SuppressWarnings("unchecked") // no support for covariance of Publisher in Java
    // needed to provide covariant conversions that the Java interfaces don't provide automatically.
    // The alternative would be having to cast around everywhere instead of doing it here in a central place.
    public static <U, T extends U> Publisher<U> convertPublisher(Publisher<T> p) {
        return (Publisher<U>)(Publisher) p;
    }
    @SuppressWarnings("unchecked")
    public static <T, U extends T> Publisher<U> upcastPublisher(Publisher<T> p) {
        return (Publisher<U>)(Publisher) p;
    }
    @SuppressWarnings("unchecked")
    public static scala.collection.immutable.Map<String, String> convertMapToScala(Map<String, String> map) {
        return Map$.MODULE$.apply(scala.collection.JavaConverters.mapAsScalaMapConverter(map).asScala().toSeq());
    }
    @SuppressWarnings("unchecked") // contains an upcast
    public static <T, U extends T> scala.Option<U> convertOptionToScala(Option<T> o) {
        return ((Option<U>) o).asScala();
    }

    public static final scala.collection.immutable.Map<String, String> emptyMap =
        Map$.MODULE$.<String, String>empty();

    public static final None$ noneValue = None$.MODULE$;
    @SuppressWarnings("unchecked")
    public static <T> scala.Option<T> scalaNone() {
        return (scala.Option<T>) noneValue;
    }

    @SuppressWarnings("unchecked")
    public static <T, U extends T> Seq<U> convertIterable(Iterable<T> els) {
        return scala.collection.JavaConverters.iterableAsScalaIterableConverter((Iterable<U>)els).asScala().toVector();
    }
    @SuppressWarnings("unchecked")
    public static <T, U extends T> Seq<U> convertArray(T[] els) {
        return Util.<T, U>convertIterable(Arrays.asList(els));
    }

    public static akka.http.model.Uri convertUriToScala(Uri uri) {
        return ((JavaUri) uri).uri();
    }

    public static <J, V extends J> akka.japi.Option<J> lookupInRegistry(ObjectRegistry<Object, V> registry, int key) {
        return Util.<J, V>convertOption(registry.getForKey(key));
    }
    public static <J, V extends J> akka.japi.Option<J> lookupInRegistry(ObjectRegistry<String, V> registry, String key) {
        return Util.<String, J, V>lookupInRegistry(registry, key);
    }
    public static <K, J, V extends J> akka.japi.Option<J> lookupInRegistry(ObjectRegistry<K, V> registry, K key) {
        return Util.<J, V>convertOption(registry.getForKey(key));
    }

    /**
     * Temporary replacement for akka.japi.Option.getOrElse until it gets released there.
     *
     * FIXME: remove in favor of a proper japi.Option.getOrElse
     */
    public static <B, A extends B> B getOrElse(Option<A> option, B defaultValue) {
        if (option.isDefined()) return option.get();
        else return defaultValue;
    }
}
