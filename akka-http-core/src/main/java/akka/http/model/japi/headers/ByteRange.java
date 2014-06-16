/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.ByteRange$;
import akka.japi.Option;

public abstract class ByteRange {
    public abstract boolean isSlice();
    public abstract boolean isFromOffset();
    public abstract boolean isSuffix();

    public abstract Option<Long> getSliceFirst();
    public abstract Option<Long> getSliceLast();
    public abstract Option<Long> getOffset();
    public abstract Option<Long> getSuffixLength();

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
