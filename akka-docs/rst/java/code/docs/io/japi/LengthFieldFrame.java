/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

//#frame
import java.nio.ByteOrder;
import java.util.ArrayList;

import scala.util.Either;
import akka.io.AbstractSymmetricPipePair;
import akka.io.PipePairFactory;
import akka.io.PipelineContext;
import akka.io.SymmetricPipePair;
import akka.io.SymmetricPipelineStage;
import akka.util.ByteString;
import akka.util.ByteStringBuilder;

public class LengthFieldFrame extends
    SymmetricPipelineStage<PipelineContext, ByteString, ByteString> {

  final int maxSize;

  public LengthFieldFrame(int maxSize) {
    this.maxSize = maxSize;
  }

  @Override
  public SymmetricPipePair<ByteString, ByteString> apply(final PipelineContext ctx) {
    return PipePairFactory
        .create(ctx, new AbstractSymmetricPipePair<ByteString, ByteString>() {

          final ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;
          ByteString buffer = null;

          @Override
          public Iterable<Either<ByteString, ByteString>> onCommand(
              ByteString cmd) {
            final int length = cmd.length() + 4;
            if (length > maxSize) {
              return new ArrayList<Either<ByteString, ByteString>>(0);
            }
            final ByteStringBuilder bb = new ByteStringBuilder();
            bb.putInt(length, byteOrder);
            bb.append(cmd);
            return singleCommand(bb.result());
          }

          @Override
          public Iterable<Either<ByteString, ByteString>> onEvent(
              ByteString event) {
            final ArrayList<Either<ByteString, ByteString>> res =
                new ArrayList<Either<ByteString, ByteString>>();
            ByteString current = buffer == null ? event : buffer.concat(event);
            while (true) {
              if (current.length() == 0) {
                buffer = null;
                return res;
              } else if (current.length() < 4) {
                buffer = current;
                return res;
              } else {
                final int length = current.iterator().getInt(byteOrder);
                if (length > maxSize)
                  throw new IllegalArgumentException(
                      "received too large frame of size " + length + " (max = "
                          + maxSize + ")");
                if (current.length() < length) {
                  buffer = current;
                  return res;
                } else {
                  res.add(makeEvent(current.slice(4, length)));
                  current = current.drop(length);
                }
              }
            }
          }

        });
  }

}
//#frame
