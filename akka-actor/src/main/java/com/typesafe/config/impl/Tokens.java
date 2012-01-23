/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.util.List;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigValueType;

/* FIXME the way the subclasses of Token are private with static isFoo and accessors is kind of ridiculous. */
final class Tokens {
    static private class Value extends Token {

        final private AbstractConfigValue value;

        Value(AbstractConfigValue value) {
            super(TokenType.VALUE, value.origin());
            this.value = value;
        }

        AbstractConfigValue value() {
            return value;
        }

        @Override
        public String toString() {
            return "'" + value().unwrapped() + "' (" + value.valueType().name() + ")";
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof Value;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other) && ((Value) other).value.equals(value);
        }

        @Override
        public int hashCode() {
            return 41 * (41 + super.hashCode()) + value.hashCode();
        }
    }

    static private class Line extends Token {
        Line(ConfigOrigin origin) {
            super(TokenType.NEWLINE, origin);
        }

        @Override
        public String toString() {
            return "'\\n'@" + lineNumber();
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof Line;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other) && ((Line) other).lineNumber() == lineNumber();
        }

        @Override
        public int hashCode() {
            return 41 * (41 + super.hashCode()) + lineNumber();
        }
    }

    // This is not a Value, because it requires special processing
    static private class UnquotedText extends Token {
        final private String value;

        UnquotedText(ConfigOrigin origin, String s) {
            super(TokenType.UNQUOTED_TEXT, origin);
            this.value = s;
        }

        String value() {
            return value;
        }

        @Override
        public String toString() {
            return "'" + value + "'";
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof UnquotedText;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other)
                    && ((UnquotedText) other).value.equals(value);
        }

        @Override
        public int hashCode() {
            return 41 * (41 + super.hashCode()) + value.hashCode();
        }
    }

    static private class Problem extends Token {
        final private String what;
        final private String message;
        final private boolean suggestQuotes;
        final private Throwable cause;

        Problem(ConfigOrigin origin, String what, String message, boolean suggestQuotes,
                Throwable cause) {
            super(TokenType.PROBLEM, origin);
            this.what = what;
            this.message = message;
            this.suggestQuotes = suggestQuotes;
            this.cause = cause;
        }

        String message() {
            return message;
        }

        boolean suggestQuotes() {
            return suggestQuotes;
        }

        Throwable cause() {
            return cause;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('\'');
            sb.append(what);
            sb.append('\'');
            return sb.toString();
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof Problem;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other) && ((Problem) other).what.equals(what)
                    && ((Problem) other).message.equals(message)
                    && ((Problem) other).suggestQuotes == suggestQuotes
                    && ConfigImplUtil.equalsHandlingNull(((Problem) other).cause, cause);
        }

        @Override
        public int hashCode() {
            int h = 41 * (41 + super.hashCode());
            h = 41 * (h + what.hashCode());
            h = 41 * (h + message.hashCode());
            h = 41 * (h + Boolean.valueOf(suggestQuotes).hashCode());
            if (cause != null)
                h = 41 * (h + cause.hashCode());
            return h;
        }
    }

    static private class Comment extends Token {
        final private String text;

        Comment(ConfigOrigin origin, String text) {
            super(TokenType.COMMENT, origin);
            this.text = text;
        }

        String text() {
            return text;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("'#");
            sb.append(text);
            sb.append("' (COMMENT)");
            return sb.toString();
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof Comment;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other) && ((Comment) other).text.equals(text);
        }

        @Override
        public int hashCode() {
            int h = 41 * (41 + super.hashCode());
            h = 41 * (h + text.hashCode());
            return h;
        }
    }

    // This is not a Value, because it requires special processing
    static private class Substitution extends Token {
        final private boolean optional;
        final private List<Token> value;

        Substitution(ConfigOrigin origin, boolean optional, List<Token> expression) {
            super(TokenType.SUBSTITUTION, origin);
            this.optional = optional;
            this.value = expression;
        }

        boolean optional() {
            return optional;
        }

        List<Token> value() {
            return value;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Token t : value) {
                sb.append(t.toString());
            }
            return "'${" + sb.toString() + "}'";
        }

        @Override
        protected boolean canEqual(Object other) {
            return other instanceof Substitution;
        }

        @Override
        public boolean equals(Object other) {
            return super.equals(other)
                    && ((Substitution) other).value.equals(value);
        }

        @Override
        public int hashCode() {
            return 41 * (41 + super.hashCode()) + value.hashCode();
        }
    }

    static boolean isValue(Token token) {
        return token instanceof Value;
    }

    static AbstractConfigValue getValue(Token token) {
        if (token instanceof Value) {
            return ((Value) token).value();
        } else {
            throw new ConfigException.BugOrBroken(
                    "tried to get value of non-value token " + token);
        }
    }

    static boolean isValueWithType(Token t, ConfigValueType valueType) {
        return isValue(t) && getValue(t).valueType() == valueType;
    }

    static boolean isNewline(Token token) {
        return token instanceof Line;
    }

    static boolean isProblem(Token token) {
        return token instanceof Problem;
    }

    static String getProblemMessage(Token token) {
        if (token instanceof Problem) {
            return ((Problem) token).message();
        } else {
            throw new ConfigException.BugOrBroken("tried to get problem message from " + token);
        }
    }

    static boolean getProblemSuggestQuotes(Token token) {
        if (token instanceof Problem) {
            return ((Problem) token).suggestQuotes();
        } else {
            throw new ConfigException.BugOrBroken("tried to get problem suggestQuotes from "
                    + token);
        }
    }

    static Throwable getProblemCause(Token token) {
        if (token instanceof Problem) {
            return ((Problem) token).cause();
        } else {
            throw new ConfigException.BugOrBroken("tried to get problem cause from " + token);
        }
    }

    static boolean isComment(Token token) {
        return token instanceof Comment;
    }

    static String getCommentText(Token token) {
        if (token instanceof Comment) {
            return ((Comment) token).text();
        } else {
            throw new ConfigException.BugOrBroken("tried to get comment text from " + token);
        }
    }

    static boolean isUnquotedText(Token token) {
        return token instanceof UnquotedText;
    }

    static String getUnquotedText(Token token) {
        if (token instanceof UnquotedText) {
            return ((UnquotedText) token).value();
        } else {
            throw new ConfigException.BugOrBroken(
                    "tried to get unquoted text from " + token);
        }
    }

    static boolean isSubstitution(Token token) {
        return token instanceof Substitution;
    }

    static List<Token> getSubstitutionPathExpression(Token token) {
        if (token instanceof Substitution) {
            return ((Substitution) token).value();
        } else {
            throw new ConfigException.BugOrBroken(
                    "tried to get substitution from " + token);
        }
    }

    static boolean getSubstitutionOptional(Token token) {
        if (token instanceof Substitution) {
            return ((Substitution) token).optional();
        } else {
            throw new ConfigException.BugOrBroken("tried to get substitution optionality from "
                    + token);
        }
    }

    final static Token START = Token.newWithoutOrigin(TokenType.START, "start of file");
    final static Token END = Token.newWithoutOrigin(TokenType.END, "end of file");
    final static Token COMMA = Token.newWithoutOrigin(TokenType.COMMA, "','");
    final static Token EQUALS = Token.newWithoutOrigin(TokenType.EQUALS, "'='");
    final static Token COLON = Token.newWithoutOrigin(TokenType.COLON, "':'");
    final static Token OPEN_CURLY = Token.newWithoutOrigin(TokenType.OPEN_CURLY, "'{'");
    final static Token CLOSE_CURLY = Token.newWithoutOrigin(TokenType.CLOSE_CURLY, "'}'");
    final static Token OPEN_SQUARE = Token.newWithoutOrigin(TokenType.OPEN_SQUARE, "'['");
    final static Token CLOSE_SQUARE = Token.newWithoutOrigin(TokenType.CLOSE_SQUARE, "']'");

    static Token newLine(ConfigOrigin origin) {
        return new Line(origin);
    }

    static Token newProblem(ConfigOrigin origin, String what, String message,
            boolean suggestQuotes, Throwable cause) {
        return new Problem(origin, what, message, suggestQuotes, cause);
    }

    static Token newComment(ConfigOrigin origin, String text) {
        return new Comment(origin, text);
    }

    static Token newUnquotedText(ConfigOrigin origin, String s) {
        return new UnquotedText(origin, s);
    }

    static Token newSubstitution(ConfigOrigin origin, boolean optional, List<Token> expression) {
        return new Substitution(origin, optional, expression);
    }

    static Token newValue(AbstractConfigValue value) {
        return new Value(value);
    }

    static Token newString(ConfigOrigin origin, String value) {
        return newValue(new ConfigString(origin, value));
    }

    static Token newInt(ConfigOrigin origin, int value, String originalText) {
        return newValue(ConfigNumber.newNumber(origin, value,
                originalText));
    }

    static Token newDouble(ConfigOrigin origin, double value,
            String originalText) {
        return newValue(ConfigNumber.newNumber(origin, value,
                originalText));
    }

    static Token newLong(ConfigOrigin origin, long value, String originalText) {
        return newValue(ConfigNumber.newNumber(origin, value,
                originalText));
    }

    static Token newNull(ConfigOrigin origin) {
        return newValue(new ConfigNull(origin));
    }

    static Token newBoolean(ConfigOrigin origin, boolean value) {
        return newValue(new ConfigBoolean(origin, value));
    }
}
