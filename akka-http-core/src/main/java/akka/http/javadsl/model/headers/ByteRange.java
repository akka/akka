/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.ByteRange$;

import java.util.OptionalLong;

public abstract class ByteRange {
    public abstract boolean isSlice();
    public abstract boolean isFromOffset();
    public abstract boolean isSuffix();

    public abstract OptionalLong getSliceFirst();
    public abstract OptionalLong getSliceLast();
    public abstract OptionalLong getOffset();
    public abstract OptionalLong getSuffixLength();

    public static ByteRange createSlice(long first, long last) {
        return ByteRange$.MODULE$.apply(first, last);
    }
    public static ByteRange createFromOffset(long offset) {
        return ByteRange$.MODULE$.fromOffset(offset);
    }
    public static ByteRange createSuffix(long length) {
        return ByteRange$.MODULE$.suffix(length);
    }
}
