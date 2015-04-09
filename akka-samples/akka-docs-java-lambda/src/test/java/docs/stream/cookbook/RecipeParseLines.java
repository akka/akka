/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import java.util.ArrayList;
import java.util.List;

import akka.stream.stage.*;
import akka.util.ByteString;

public class RecipeParseLines {

  public static StatefulStage<ByteString, String> parseLines(String separator, int maximumLineBytes) {
    return new StatefulStage<ByteString, String>() {
      
      final ByteString separatorBytes = ByteString.fromString(separator);
      final byte firstSeparatorByte = separatorBytes.head();

      @Override
      public StageState<ByteString, String> initial() {
        return new StageState<ByteString, String>() {
          ByteString buffer = ByteString.empty();
          int nextPossibleMatch = 0;
          
          @Override
          public SyncDirective onPush(ByteString chunk, Context<String> ctx) {
            buffer = buffer.concat(chunk);
            if (buffer.size() > maximumLineBytes) {
              return ctx.fail(new IllegalStateException("Read " + buffer.size()  + " bytes " +
                "which is more than " + maximumLineBytes + " without seeing a line terminator"));
            } else { 
              return emit(doParse().iterator(), ctx);
            }
          }

          private List<String> doParse() {
            List<String> parsedLinesSoFar = new ArrayList<String>();
            while (true) {
              int possibleMatchPos = buffer.indexOf(firstSeparatorByte, nextPossibleMatch);
              if (possibleMatchPos == -1) {
                // No matching character, we need to accumulate more bytes into the buffer
                nextPossibleMatch = buffer.size();
                break;
              } else if (possibleMatchPos + separatorBytes.size() > buffer.size()) {
                // We have found a possible match (we found the first character of the terminator
                // sequence) but we don't have yet enough bytes. We remember the position to
                // retry from next time.
                nextPossibleMatch = possibleMatchPos;
                break;
              } else {
                if (buffer.slice(possibleMatchPos, possibleMatchPos + separatorBytes.size())
                    .equals(separatorBytes)) {
                  // Found a match
                  String parsedLine = buffer.slice(0, possibleMatchPos).utf8String();
                  buffer = buffer.drop(possibleMatchPos + separatorBytes.size());
                  nextPossibleMatch -= possibleMatchPos + separatorBytes.size();
                  parsedLinesSoFar.add(parsedLine);
                } else {
                  nextPossibleMatch += 1;
                }
              }
            }
            return parsedLinesSoFar;
          }
          
        };
      }
      
    };
  }

}
