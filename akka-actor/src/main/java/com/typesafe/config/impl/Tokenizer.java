/**
 *   Copyright (C) 2011 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigSyntax;

final class Tokenizer {
    /**
     * Tokenizes a Reader. Does not close the reader; you have to arrange to do
     * that after you're done with the returned iterator.
     */
    static Iterator<Token> tokenize(ConfigOrigin origin, Reader input, ConfigSyntax flavor) {
        return new TokenIterator(origin, input, flavor != ConfigSyntax.JSON);
    }

    private static class TokenIterator implements Iterator<Token> {

        private static class WhitespaceSaver {
            // has to be saved inside value concatenations
            private StringBuilder whitespace;
            // may need to value-concat with next value
            private boolean lastTokenWasSimpleValue;

            WhitespaceSaver() {
                whitespace = new StringBuilder();
                lastTokenWasSimpleValue = false;
            }

            void add(int c) {
                if (lastTokenWasSimpleValue)
                    whitespace.appendCodePoint(c);
            }

            Token check(Token t, ConfigOrigin baseOrigin, int lineNumber) {
                if (isSimpleValue(t)) {
                    return nextIsASimpleValue(baseOrigin, lineNumber);
                } else {
                    nextIsNotASimpleValue();
                    return null;
                }
            }

            // called if the next token is not a simple value;
            // discards any whitespace we were saving between
            // simple values.
            private void nextIsNotASimpleValue() {
                lastTokenWasSimpleValue = false;
                whitespace.setLength(0);
            }

            // called if the next token IS a simple value,
            // so creates a whitespace token if the previous
            // token also was.
            private Token nextIsASimpleValue(ConfigOrigin baseOrigin,
                    int lineNumber) {
                if (lastTokenWasSimpleValue) {
                    // need to save whitespace between the two so
                    // the parser has the option to concatenate it.
                    if (whitespace.length() > 0) {
                        Token t = Tokens.newUnquotedText(
                                lineOrigin(baseOrigin, lineNumber),
                                whitespace.toString());
                        whitespace.setLength(0); // reset
                        return t;
                    } else {
                        // lastTokenWasSimpleValue = true still
                        return null;
                    }
                } else {
                    lastTokenWasSimpleValue = true;
                    whitespace.setLength(0);
                    return null;
                }
            }
        }

        final private ConfigOrigin origin;
        final private Reader input;
        final private LinkedList<Integer> buffer;
        private int lineNumber;
        final private Queue<Token> tokens;
        final private WhitespaceSaver whitespaceSaver;
        final private boolean allowComments;

        TokenIterator(ConfigOrigin origin, Reader input, boolean allowComments) {
            this.origin = origin;
            this.input = input;
            this.allowComments = allowComments;
            this.buffer = new LinkedList<Integer>();
            lineNumber = 1;
            tokens = new LinkedList<Token>();
            tokens.add(Tokens.START);
            whitespaceSaver = new WhitespaceSaver();
        }


        // this should ONLY be called from nextCharSkippingComments
        // or when inside a quoted string, everything else should
        // use nextCharSkippingComments().
        private int nextCharRaw() {
            if (buffer.isEmpty()) {
                try {
                    return input.read();
                } catch (IOException e) {
                    throw new ConfigException.IO(origin, "read error: "
                            + e.getMessage(), e);
                }
            } else {
                int c = buffer.pop();
                return c;
            }
        }

        private void putBack(int c) {
            if (buffer.size() > 2) {
                throw new ConfigException.BugOrBroken(
                        "bug: putBack() three times, undesirable look-ahead");
            }
            buffer.push(c);
        }

        static boolean isWhitespace(int c) {
            return ConfigUtil.isWhitespace(c);
        }

        static boolean isWhitespaceNotNewline(int c) {
            return c != '\n' && ConfigUtil.isWhitespace(c);
        }

        private int slurpComment() {
            for (;;) {
                int c = nextCharRaw();
                if (c == -1 || c == '\n') {
                    return c;
                }
            }
        }

        // get next char, skipping comments
        private int nextCharSkippingComments() {
            for (;;) {
                int c = nextCharRaw();

                if (c == -1) {
                    return -1;
                } else {
                    if (allowComments) {
                        if (c == '#') {
                            return slurpComment();
                        } else if (c == '/') {
                            int maybeSecondSlash = nextCharRaw();
                            if (maybeSecondSlash == '/') {
                                return slurpComment();
                            } else {
                                putBack(maybeSecondSlash);
                                return c;
                            }
                        } else {
                            return c;
                        }
                    } else {
                        return c;
                    }
                }
            }
        }

        // get next char, skipping non-newline whitespace
        private int nextCharAfterWhitespace(WhitespaceSaver saver) {
            for (;;) {
                int c = nextCharSkippingComments();

                if (c == -1) {
                    return -1;
                } else {
                    if (isWhitespaceNotNewline(c)) {
                        saver.add(c);
                        continue;
                    } else {
                        return c;
                    }
                }
            }
        }

        private ConfigException parseError(String message) {
            return parseError(message, null);
        }

        private ConfigException parseError(String message, Throwable cause) {
            return parseError(lineOrigin(), message, cause);
        }

        private static ConfigException parseError(ConfigOrigin origin,
                String message,
                Throwable cause) {
            return new ConfigException.Parse(origin, message, cause);
        }

        private static ConfigException parseError(ConfigOrigin origin,
                String message) {
            return parseError(origin, message, null);
        }

        private ConfigOrigin lineOrigin() {
            return lineOrigin(origin, lineNumber);
        }

        private static ConfigOrigin lineOrigin(ConfigOrigin baseOrigin,
                int lineNumber) {
            return new SimpleConfigOrigin(baseOrigin.description() + ": line "
                    + lineNumber);
        }

        // chars JSON allows a number to start with
        static final String firstNumberChars = "0123456789-";
        // chars JSON allows to be part of a number
        static final String numberChars = "0123456789eE+-.";
        // chars that stop an unquoted string
        static final String notInUnquotedText = "$\"{}[]:=,\\+#";

        // The rules here are intended to maximize convenience while
        // avoiding confusion with real valid JSON. Basically anything
        // that parses as JSON is treated the JSON way and otherwise
        // we assume it's a string and let the parser sort it out.
        private Token pullUnquotedText() {
            ConfigOrigin origin = lineOrigin();
            StringBuilder sb = new StringBuilder();
            int c = nextCharSkippingComments();
            while (true) {
                if (c == -1) {
                    break;
                } else if (notInUnquotedText.indexOf(c) >= 0) {
                    break;
                } else if (isWhitespace(c)) {
                    break;
                } else {
                    sb.appendCodePoint(c);
                }

                // we parse true/false/null tokens as such no matter
                // what is after them, as long as they are at the
                // start of the unquoted token.
                if (sb.length() == 4) {
                    String s = sb.toString();
                    if (s.equals("true"))
                        return Tokens.newBoolean(origin, true);
                    else if (s.equals("null"))
                        return Tokens.newNull(origin);
                } else if (sb.length() == 5) {
                    String s = sb.toString();
                    if (s.equals("false"))
                        return Tokens.newBoolean(origin, false);
                }

                c = nextCharSkippingComments();
            }

            // put back the char that ended the unquoted text
            putBack(c);

            String s = sb.toString();
            return Tokens.newUnquotedText(origin, s);
        }

        private Token pullNumber(int firstChar) {
            StringBuilder sb = new StringBuilder();
            sb.appendCodePoint(firstChar);
            boolean containedDecimalOrE = false;
            int c = nextCharSkippingComments();
            while (c != -1 && numberChars.indexOf(c) >= 0) {
                if (c == '.' || c == 'e' || c == 'E')
                    containedDecimalOrE = true;
                sb.appendCodePoint(c);
                c = nextCharSkippingComments();
            }
            // the last character we looked at wasn't part of the number, put it
            // back
            putBack(c);
            String s = sb.toString();
            try {
                if (containedDecimalOrE) {
                    // force floating point representation
                    return Tokens.newDouble(lineOrigin(),
                            Double.parseDouble(s), s);
                } else {
                    // this should throw if the integer is too large for Long
                    return Tokens.newLong(lineOrigin(), Long.parseLong(s), s);
                }
            } catch (NumberFormatException e) {
                throw parseError("Invalid number: '" + s
                                + "' (if this is in a path, try quoting it with double quotes)",
                        e);
            }
        }

        private void pullEscapeSequence(StringBuilder sb) {
            int escaped = nextCharRaw();
            if (escaped == -1)
                throw parseError("End of input but backslash in string had nothing after it");

            switch (escaped) {
            case '"':
                sb.append('"');
                break;
            case '\\':
                sb.append('\\');
                break;
            case '/':
                sb.append('/');
                break;
            case 'b':
                sb.append('\b');
                break;
            case 'f':
                sb.append('\f');
                break;
            case 'n':
                sb.append('\n');
                break;
            case 'r':
                sb.append('\r');
                break;
            case 't':
                sb.append('\t');
                break;
            case 'u': {
                // kind of absurdly slow, but screw it for now
                char[] a = new char[4];
                for (int i = 0; i < 4; ++i) {
                    int c = nextCharSkippingComments();
                    if (c == -1)
                        throw parseError("End of input but expecting 4 hex digits for \\uXXXX escape");
                    a[i] = (char) c;
                }
                String digits = new String(a);
                try {
                    sb.appendCodePoint(Integer.parseInt(digits, 16));
                } catch (NumberFormatException e) {
                    throw parseError(
                            String.format(
                                    "Malformed hex digits after \\u escape in string: '%s'",
                                    digits), e);
                }
            }
                break;
            default:
                throw parseError(String
                        .format("backslash followed by '%c', this is not a valid escape sequence",
                                escaped));
            }
        }

        private ConfigException controlCharacterError(int c) {
            String asString;
            if (c == '\n')
                asString = "newline";
            else if (c == '\t')
                asString = "tab";
            else
                asString = String.format("control character 0x%x", c);
            return parseError("JSON does not allow unescaped " + asString
                    + " in quoted strings, use a backslash escape");
        }

        private Token pullQuotedString() {
            // the open quote has already been consumed
            StringBuilder sb = new StringBuilder();
            int c = '\0'; // value doesn't get used
            do {
                c = nextCharRaw();
                if (c == -1)
                    throw parseError("End of input but string quote was still open");

                if (c == '\\') {
                    pullEscapeSequence(sb);
                } else if (c == '"') {
                    // end the loop, done!
                } else if (Character.isISOControl(c)) {
                    throw controlCharacterError(c);
                } else {
                    sb.appendCodePoint(c);
                }
            } while (c != '"');
            return Tokens.newString(lineOrigin(), sb.toString());
        }

        private Token pullSubstitution() {
            // the initial '$' has already been consumed
            ConfigOrigin origin = lineOrigin();
            int c = nextCharSkippingComments();
            if (c != '{') {
                throw parseError("'$' not followed by {");
            }

            WhitespaceSaver saver = new WhitespaceSaver();
            List<Token> expression = new ArrayList<Token>();

            Token t;
            do {
                t = pullNextToken(saver);

                // note that we avoid validating the allowed tokens inside
                // the substitution here; we even allow nested substitutions
                // in the tokenizer. The parser sorts it out.
                if (t == Tokens.CLOSE_CURLY) {
                    // end the loop, done!
                    break;
                } else if (t == Tokens.END) {
                    throw parseError(origin,
                            "Substitution ${ was not closed with a }");
                } else {
                    Token whitespace = saver.check(t, origin, lineNumber);
                    if (whitespace != null)
                        expression.add(whitespace);
                    expression.add(t);
                }
            } while (true);

            return Tokens.newSubstitution(origin, expression);
        }

        private Token pullNextToken(WhitespaceSaver saver) {
            int c = nextCharAfterWhitespace(saver);
            if (c == -1) {
                return Tokens.END;
            } else if (c == '\n') {
                // newline tokens have the just-ended line number
                lineNumber += 1;
                return Tokens.newLine(lineNumber - 1);
            } else {
                Token t = null;
                switch (c) {
                case '"':
                    t = pullQuotedString();
                    break;
                case '$':
                    t = pullSubstitution();
                    break;
                case ':':
                    t = Tokens.COLON;
                    break;
                case ',':
                    t = Tokens.COMMA;
                    break;
                case '=':
                    t = Tokens.EQUALS;
                    break;
                case '{':
                    t = Tokens.OPEN_CURLY;
                    break;
                case '}':
                    t = Tokens.CLOSE_CURLY;
                    break;
                case '[':
                    t = Tokens.OPEN_SQUARE;
                    break;
                case ']':
                    t = Tokens.CLOSE_SQUARE;
                    break;
                }

                if (t == null) {
                    if (firstNumberChars.indexOf(c) >= 0) {
                        t = pullNumber(c);
                    } else if (notInUnquotedText.indexOf(c) >= 0) {
                        throw parseError(String
                                .format("Character '%c' is not the start of any valid token",
                                        c));
                    } else {
                        putBack(c);
                        t = pullUnquotedText();
                    }
                }

                if (t == null)
                    throw new ConfigException.BugOrBroken(
                            "bug: failed to generate next token");

                return t;
            }
        }

        private static boolean isSimpleValue(Token t) {
            if (Tokens.isSubstitution(t) || Tokens.isUnquotedText(t)
                    || Tokens.isValue(t)) {
                return true;
            } else {
                return false;
            }
        }

        private void queueNextToken() {
            Token t = pullNextToken(whitespaceSaver);
            Token whitespace = whitespaceSaver.check(t, origin, lineNumber);
            if (whitespace != null)
                tokens.add(whitespace);
            tokens.add(t);
        }

        @Override
        public boolean hasNext() {
            return !tokens.isEmpty();
        }

        @Override
        public Token next() {
            Token t = tokens.remove();
            if (tokens.isEmpty() && t != Tokens.END) {
                queueNextToken();
                if (tokens.isEmpty())
                    throw new ConfigException.BugOrBroken(
                            "bug: tokens queue should not be empty here");
            }
            return t;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException(
                    "Does not make sense to remove items from token stream");
        }
    }
}
