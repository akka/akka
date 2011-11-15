package com.typesafe.config.impl;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Stack;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigIncludeContext;
import com.typesafe.config.ConfigIncluder;
import com.typesafe.config.ConfigOrigin;
import com.typesafe.config.ConfigParseOptions;
import com.typesafe.config.ConfigSyntax;
import com.typesafe.config.ConfigValueType;

final class Parser {

    static AbstractConfigValue parse(Iterator<Token> tokens,
            ConfigOrigin origin, ConfigParseOptions options,
            ConfigIncludeContext includeContext) {
        ParseContext context = new ParseContext(options.getSyntax(), origin,
                tokens, options.getIncluder(), includeContext);
        return context.parse();
    }

    static private final class ParseContext {
        private int lineNumber;
        final private Stack<Token> buffer;
        final private Iterator<Token> tokens;
        final private ConfigIncluder includer;
        final private ConfigIncludeContext includeContext;
        final private ConfigSyntax flavor;
        final private ConfigOrigin baseOrigin;
        final private LinkedList<Path> pathStack;

        ParseContext(ConfigSyntax flavor, ConfigOrigin origin,
                Iterator<Token> tokens, ConfigIncluder includer,
                ConfigIncludeContext includeContext) {
            lineNumber = 1;
            buffer = new Stack<Token>();
            this.tokens = tokens;
            this.flavor = flavor;
            this.baseOrigin = origin;
            this.includer = includer;
            this.includeContext = includeContext;
            this.pathStack = new LinkedList<Path>();
        }

        private Token nextToken() {
            Token t = null;
            if (buffer.isEmpty()) {
                t = tokens.next();
            } else {
                t = buffer.pop();
            }

            if (flavor == ConfigSyntax.JSON) {
                if (Tokens.isUnquotedText(t)) {
                    throw parseError("Token not allowed in valid JSON: '"
                        + Tokens.getUnquotedText(t) + "'");
                } else if (Tokens.isSubstitution(t)) {
                    throw parseError("Substitutions (${} syntax) not allowed in JSON");
                }
            }

            return t;
        }

        private void putBack(Token token) {
            buffer.push(token);
        }

        private Token nextTokenIgnoringNewline() {
            Token t = nextToken();
            while (Tokens.isNewline(t)) {
                // line number tokens have the line that was _ended_ by the
                // newline, so we have to add one.
                lineNumber = Tokens.getLineNumber(t) + 1;
                t = nextToken();
            }
            return t;
        }

        // In arrays and objects, comma can be omitted
        // as long as there's at least one newline instead.
        // this skips any newlines in front of a comma,
        // skips the comma, and returns true if it found
        // either a newline or a comma. The iterator
        // is left just after the comma or the newline.
        private boolean checkElementSeparator() {
            if (flavor == ConfigSyntax.JSON) {
                Token t = nextTokenIgnoringNewline();
                if (t == Tokens.COMMA) {
                    return true;
                } else {
                    putBack(t);
                    return false;
                }
            } else {
                boolean sawSeparatorOrNewline = false;
                Token t = nextToken();
                while (true) {
                    if (Tokens.isNewline(t)) {
                        lineNumber = Tokens.getLineNumber(t);
                        sawSeparatorOrNewline = true;
                        // we want to continue to also eat
                        // a comma if there is one.
                    } else if (t == Tokens.COMMA) {
                        return true;
                    } else {
                        // non-newline-or-comma
                        putBack(t);
                        return sawSeparatorOrNewline;
                    }
                    t = nextToken();
                }
            }
        }

        // merge a bunch of adjacent values into one
        // value; change unquoted text into a string
        // value.
        private void consolidateValueTokens() {
            // this trick is not done in JSON
            if (flavor == ConfigSyntax.JSON)
                return;

            List<Token> values = null; // create only if we have value tokens
            Token t = nextTokenIgnoringNewline(); // ignore a newline up front
            while (Tokens.isValue(t) || Tokens.isUnquotedText(t)
                    || Tokens.isSubstitution(t)) {
                if (values == null)
                    values = new ArrayList<Token>();
                values.add(t);
                t = nextToken(); // but don't consolidate across a newline
            }
            // the last one wasn't a value token
            putBack(t);

            if (values == null)
                return;

            if (values.size() == 1 && Tokens.isValue(values.get(0))) {
                // a single value token requires no consolidation
                putBack(values.get(0));
                return;
            }

            // this will be a list of String and Path
            List<Object> minimized = new ArrayList<Object>();

            // we have multiple value tokens or one unquoted text token;
            // collapse into a string token.
            StringBuilder sb = new StringBuilder();
            ConfigOrigin firstOrigin = null;
            for (Token valueToken : values) {
                if (Tokens.isValue(valueToken)) {
                    AbstractConfigValue v = Tokens.getValue(valueToken);
                    sb.append(v.transformToString());
                    if (firstOrigin == null)
                        firstOrigin = v.origin();
                } else if (Tokens.isUnquotedText(valueToken)) {
                    String text = Tokens.getUnquotedText(valueToken);
                    if (firstOrigin == null)
                        firstOrigin = Tokens.getUnquotedTextOrigin(valueToken);
                    sb.append(text);
                } else if (Tokens.isSubstitution(valueToken)) {
                    if (firstOrigin == null)
                        firstOrigin = Tokens.getSubstitutionOrigin(valueToken);

                    if (sb.length() > 0) {
                        // save string so far
                        minimized.add(sb.toString());
                        sb.setLength(0);
                    }
                    // now save substitution
                    List<Token> expression = Tokens
                            .getSubstitutionPathExpression(valueToken);
                    Path path = parsePathExpression(expression.iterator(),
                            Tokens.getSubstitutionOrigin(valueToken));
                    minimized.add(path);
                } else {
                    throw new ConfigException.BugOrBroken(
                            "should not be trying to consolidate token: "
                                    + valueToken);
                }
            }

            if (sb.length() > 0) {
                // save string so far
                minimized.add(sb.toString());
            }

            if (minimized.isEmpty())
                throw new ConfigException.BugOrBroken(
                        "trying to consolidate values to nothing");

            Token consolidated = null;

            if (minimized.size() == 1 && minimized.get(0) instanceof String) {
                consolidated = Tokens.newString(firstOrigin,
                        (String) minimized.get(0));
            } else {
                // there's some substitution to do later (post-parse step)
                consolidated = Tokens.newValue(new ConfigSubstitution(
                        firstOrigin, minimized));
            }

            putBack(consolidated);
        }

        private ConfigOrigin lineOrigin() {
            return new SimpleConfigOrigin(baseOrigin.description() + ": line "
                    + lineNumber);
        }

        private ConfigException parseError(String message) {
            return parseError(message, null);
        }

        private ConfigException parseError(String message, Throwable cause) {
            return new ConfigException.Parse(lineOrigin(), message, cause);
        }

        private AbstractConfigValue parseValue(Token token) {
            if (Tokens.isValue(token)) {
                return Tokens.getValue(token);
            } else if (token == Tokens.OPEN_CURLY) {
                return parseObject(true);
            } else if (token == Tokens.OPEN_SQUARE) {
                return parseArray();
            } else {
                throw parseError("Expecting a value but got wrong token: "
                        + token);
            }
        }

        private static AbstractConfigObject createValueUnderPath(Path path,
                AbstractConfigValue value) {
            // for path foo.bar, we are creating
            // { "foo" : { "bar" : value } }
            List<String> keys = new ArrayList<String>();

            String key = path.first();
            Path remaining = path.remainder();
            while (key != null) {
                keys.add(key);
                if (remaining == null) {
                    break;
                } else {
                    key = remaining.first();
                    remaining = remaining.remainder();
                }
            }
            ListIterator<String> i = keys.listIterator(keys.size());
            String deepest = i.previous();
            AbstractConfigObject o = new SimpleConfigObject(value.origin(),
                    Collections.<String, AbstractConfigValue> singletonMap(
                            deepest, value));
            while (i.hasPrevious()) {
                Map<String, AbstractConfigValue> m = Collections.<String, AbstractConfigValue> singletonMap(
                        i.previous(), o);
                o = new SimpleConfigObject(value.origin(), m);
            }

            return o;
        }

        private Path parseKey(Token token) {
            if (flavor == ConfigSyntax.JSON) {
                if (Tokens.isValueWithType(token, ConfigValueType.STRING)) {
                    String key = (String) Tokens.getValue(token).unwrapped();
                    return Path.newKey(key);
                } else {
                    throw parseError("Expecting close brace } or a field name, got "
                            + token);
                }
            } else {
                List<Token> expression = new ArrayList<Token>();
                Token t = token;
                while (Tokens.isValue(t) || Tokens.isUnquotedText(t)) {
                    expression.add(t);
                    t = nextToken(); // note: don't cross a newline
                }
                putBack(t); // put back the token we ended with
                return parsePathExpression(expression.iterator(), lineOrigin());
            }
        }

        private static boolean isIncludeKeyword(Token t) {
            return Tokens.isUnquotedText(t)
                    && Tokens.getUnquotedText(t).equals("include");
        }

        private static boolean isUnquotedWhitespace(Token t) {
            if (!Tokens.isUnquotedText(t))
                return false;

            String s = Tokens.getUnquotedText(t);

            for (int i = 0; i < s.length(); ++i) {
                char c = s.charAt(i);
                if (!ConfigUtil.isWhitespace(c))
                    return false;
            }
            return true;
        }

        private void parseInclude(Map<String, AbstractConfigValue> values) {
            Token t = nextTokenIgnoringNewline();
            while (isUnquotedWhitespace(t)) {
                t = nextTokenIgnoringNewline();
            }

            if (Tokens.isValueWithType(t, ConfigValueType.STRING)) {
                String name = (String) Tokens.getValue(t).unwrapped();
                AbstractConfigObject obj = (AbstractConfigObject) includer
                        .include(includeContext, name);

                if (!pathStack.isEmpty()) {
                    Path prefix = new Path(pathStack);
                    obj = obj.relativized(prefix);
                }

                for (String key : obj.keySet()) {
                    AbstractConfigValue v = obj.get(key);
                    AbstractConfigValue existing = values.get(key);
                    if (existing != null) {
                        values.put(key, v.withFallback(existing));
                    } else {
                        values.put(key, v);
                    }
                }

            } else {
                throw parseError("include keyword is not followed by a quoted string, but by: "
                        + t);
            }
        }

        private boolean isKeyValueSeparatorToken(Token t) {
            if (flavor == ConfigSyntax.JSON) {
                return t == Tokens.COLON;
            } else {
                return t == Tokens.COLON || t == Tokens.EQUALS;
            }
        }

        private AbstractConfigObject parseObject(boolean hadOpenCurly) {
            // invoked just after the OPEN_CURLY (or START, if !hadOpenCurly)
            Map<String, AbstractConfigValue> values = new HashMap<String, AbstractConfigValue>();
            ConfigOrigin objectOrigin = lineOrigin();
            boolean afterComma = false;
            while (true) {
                Token t = nextTokenIgnoringNewline();
                if (t == Tokens.CLOSE_CURLY) {
                    if (flavor == ConfigSyntax.JSON && afterComma) {
                        throw parseError("expecting a field name after comma, got a close brace }");
                    } else if (!hadOpenCurly) {
                        throw parseError("unbalanced close brace '}' with no open brace");
                    }
                    break;
                } else if (t == Tokens.END && !hadOpenCurly) {
                    putBack(t);
                    break;
                } else if (flavor != ConfigSyntax.JSON && isIncludeKeyword(t)) {
                    parseInclude(values);

                    afterComma = false;
                } else {
                    Path path = parseKey(t);
                    Token afterKey = nextTokenIgnoringNewline();

                    // path must be on-stack while we parse the value
                    pathStack.push(path);

                    Token valueToken;
                    AbstractConfigValue newValue;
                    if (flavor == ConfigSyntax.CONF
                            && afterKey == Tokens.OPEN_CURLY) {
                        // can omit the ':' or '=' before an object value
                        valueToken = afterKey;
                        newValue = parseObject(true);
                    } else {
                        if (!isKeyValueSeparatorToken(afterKey)) {
                            throw parseError("Key may not be followed by token: "
                                    + afterKey);
                        }

                        consolidateValueTokens();
                        valueToken = nextTokenIgnoringNewline();
                        newValue = parseValue(valueToken);
                    }

                    pathStack.pop();

                    String key = path.first();
                    Path remaining = path.remainder();

                    if (remaining == null) {
                        AbstractConfigValue existing = values.get(key);
                        if (existing != null) {
                            // In strict JSON, dups should be an error; while in
                            // our custom config language, they should be merged
                            // if the value is an object (or substitution that
                            // could become an object).

                            if (flavor == ConfigSyntax.JSON) {
                                throw parseError("JSON does not allow duplicate fields: '"
                                    + key
                                    + "' was already seen at "
                                    + existing.origin().description());
                            } else {
                                newValue = newValue.withFallback(existing);
                            }
                        }
                        values.put(key, newValue);
                    } else {
                        if (flavor == ConfigSyntax.JSON) {
                            throw new ConfigException.BugOrBroken(
                                    "somehow got multi-element path in JSON mode");
                        }

                        AbstractConfigObject obj = createValueUnderPath(
                                remaining, newValue);
                        AbstractConfigValue existing = values.get(key);
                        if (existing != null) {
                            obj = obj.withFallback(existing);
                        }
                        values.put(key, obj);
                    }

                    afterComma = false;
                }

                if (checkElementSeparator()) {
                    // continue looping
                    afterComma = true;
                } else {
                    t = nextTokenIgnoringNewline();
                    if (t == Tokens.CLOSE_CURLY) {
                        if (!hadOpenCurly) {
                            throw parseError("unbalanced close brace '}' with no open brace");
                        }
                        break;
                    } else if (hadOpenCurly) {
                        throw parseError("Expecting close brace } or a comma, got "
                                + t);
                    } else {
                        if (t == Tokens.END) {
                            putBack(t);
                            break;
                        } else {
                            throw parseError("Expecting end of input or a comma, got "
                                    + t);
                        }
                    }
                }
            }
            return new SimpleConfigObject(objectOrigin,
                    values);
        }

        private SimpleConfigList parseArray() {
            // invoked just after the OPEN_SQUARE
            ConfigOrigin arrayOrigin = lineOrigin();
            List<AbstractConfigValue> values = new ArrayList<AbstractConfigValue>();

            consolidateValueTokens();

            Token t = nextTokenIgnoringNewline();

            // special-case the first element
            if (t == Tokens.CLOSE_SQUARE) {
                return new SimpleConfigList(arrayOrigin,
                        Collections.<AbstractConfigValue> emptyList());
            } else if (Tokens.isValue(t)) {
                values.add(parseValue(t));
            } else if (t == Tokens.OPEN_CURLY) {
                values.add(parseObject(true));
            } else if (t == Tokens.OPEN_SQUARE) {
                values.add(parseArray());
            } else {
                throw parseError("List should have ] or a first element after the open [, instead had token: "
                        + t);
            }

            // now remaining elements
            while (true) {
                // just after a value
                if (checkElementSeparator()) {
                    // comma (or newline equivalent) consumed
                } else {
                    t = nextTokenIgnoringNewline();
                    if (t == Tokens.CLOSE_SQUARE) {
                        return new SimpleConfigList(arrayOrigin, values);
                    } else {
                        throw parseError("List should have ended with ] or had a comma, instead had token: "
                                + t);
                    }
                }

                // now just after a comma
                consolidateValueTokens();

                t = nextTokenIgnoringNewline();
                if (Tokens.isValue(t)) {
                    values.add(parseValue(t));
                } else if (t == Tokens.OPEN_CURLY) {
                    values.add(parseObject(true));
                } else if (t == Tokens.OPEN_SQUARE) {
                    values.add(parseArray());
                } else if (flavor != ConfigSyntax.JSON
                        && t == Tokens.CLOSE_SQUARE) {
                    // we allow one trailing comma
                    putBack(t);
                } else {
                    throw parseError("List should have had new element after a comma, instead had token: "
                            + t);
                }
            }
        }

        AbstractConfigValue parse() {
            Token t = nextTokenIgnoringNewline();
            if (t == Tokens.START) {
                // OK
            } else {
                throw new ConfigException.BugOrBroken(
                        "token stream did not begin with START, had " + t);
            }

            t = nextTokenIgnoringNewline();
            AbstractConfigValue result = null;
            if (t == Tokens.OPEN_CURLY) {
                result = parseObject(true);
            } else if (t == Tokens.OPEN_SQUARE) {
                result = parseArray();
            } else {
                if (flavor == ConfigSyntax.JSON) {
                    if (t == Tokens.END) {
                        throw parseError("Empty document");
                    } else {
                        throw parseError("Document must have an object or array at root, unexpected token: "
                                + t);
                    }
                } else {
                    // the root object can omit the surrounding braces.
                    // this token should be the first field's key, or part
                    // of it, so put it back.
                    putBack(t);
                    result = parseObject(false);
                }
            }

            t = nextTokenIgnoringNewline();
            if (t == Tokens.END) {
                return result;
            } else {
                throw parseError("Document has trailing tokens after first object or array: "
                        + t);
            }
        }
    }

    static class Element {
        StringBuilder sb;
        // an element can be empty if it has a quoted empty string "" in it
        boolean canBeEmpty;

        Element(String initial, boolean canBeEmpty) {
            this.canBeEmpty = canBeEmpty;
            this.sb = new StringBuilder(initial);
        }

        @Override
        public String toString() {
            return "Element(" + sb.toString() + "," + canBeEmpty + ")";
        }
    }

    private static void addPathText(List<Element> buf, boolean wasQuoted,
            String newText) {
        int i = wasQuoted ? -1 : newText.indexOf('.');
        Element current = buf.get(buf.size() - 1);
        if (i < 0) {
            // add to current path element
            current.sb.append(newText);
            // any empty quoted string means this element can
            // now be empty.
            if (wasQuoted && current.sb.length() == 0)
                current.canBeEmpty = true;
        } else {
            // "buf" plus up to the period is an element
            current.sb.append(newText.substring(0, i));
            // then start a new element
            buf.add(new Element("", false));
            // recurse to consume remainder of newText
            addPathText(buf, false, newText.substring(i + 1));
        }
    }

    private static Path parsePathExpression(Iterator<Token> expression,
            ConfigOrigin origin) {
        return parsePathExpression(expression, origin, null);
    }

    // originalText may be null if not available
    private static Path parsePathExpression(Iterator<Token> expression,
            ConfigOrigin origin, String originalText) {
        // each builder in "buf" is an element in the path.
        List<Element> buf = new ArrayList<Element>();
        buf.add(new Element("", false));

        if (!expression.hasNext()) {
            throw new ConfigException.BadPath(origin, originalText,
                    "Expecting a field name or path here, but got nothing");
        }

        while (expression.hasNext()) {
            Token t = expression.next();
            if (Tokens.isValueWithType(t, ConfigValueType.STRING)) {
                AbstractConfigValue v = Tokens.getValue(t);
                // this is a quoted string; so any periods
                // in here don't count as path separators
                String s = v.transformToString();

                addPathText(buf, true, s);
            } else if (t == Tokens.END) {
                // ignore this; when parsing a file, it should not happen
                // since we're parsing a token list rather than the main
                // token iterator, and when parsing a path expression from the
                // API, it's expected to have an END.
            } else {
                // any periods outside of a quoted string count as
                // separators
                String text;
                if (Tokens.isValue(t)) {
                    // appending a number here may add
                    // a period, but we _do_ count those as path
                    // separators, because we basically want
                    // "foo 3.0bar" to parse as a string even
                    // though there's a number in it. The fact that
                    // we tokenize non-string values is largely an
                    // implementation detail.
                    AbstractConfigValue v = Tokens.getValue(t);
                    text = v.transformToString();
                } else if (Tokens.isUnquotedText(t)) {
                    text = Tokens.getUnquotedText(t);
                } else {
                    throw new ConfigException.BadPath(origin, originalText,
                            "Token not allowed in path expression: "
                            + t);
                }

                addPathText(buf, false, text);
            }
        }

        PathBuilder pb = new PathBuilder();
        for (Element e : buf) {
            if (e.sb.length() == 0 && !e.canBeEmpty) {
                throw new ConfigException.BadPath(
                        origin,
                        originalText,
                        "path has a leading, trailing, or two adjacent period '.' (use quoted \"\" empty string if you want an empty element)");
            } else {
                pb.appendKey(e.sb.toString());
            }
        }

        return pb.result();
    }

    static ConfigOrigin apiOrigin = new SimpleConfigOrigin("path parameter");

    static Path parsePath(String path) {
        Path speculated = speculativeFastParsePath(path);
        if (speculated != null)
            return speculated;

        StringReader reader = new StringReader(path);

        try {
            Iterator<Token> tokens = Tokenizer.tokenize(apiOrigin, reader,
                    ConfigSyntax.CONF);
            tokens.next(); // drop START
            return parsePathExpression(tokens, apiOrigin, path);
        } finally {
            reader.close();
        }
    }

    // the idea is to see if the string has any chars that might require the
    // full parser to deal with.
    private static boolean hasUnsafeChars(String s) {
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || c == '.')
                continue;
            else
                return true;
        }
        return false;
    }

    private static void appendPathString(PathBuilder pb, String s) {
        int splitAt = s.indexOf('.');
        if (splitAt < 0) {
            pb.appendKey(s);
        } else {
            pb.appendKey(s.substring(0, splitAt));
            appendPathString(pb, s.substring(splitAt + 1));
        }
    }

    // do something much faster than the full parser if
    // we just have something like "foo" or "foo.bar"
    private static Path speculativeFastParsePath(String path) {
        String s = ConfigUtil.unicodeTrim(path);
        if (s.isEmpty())
            return null;
        if (hasUnsafeChars(s))
            return null;
        if (s.startsWith(".") || s.endsWith(".") || s.contains(".."))
            return null; // let the full parser throw the error

        PathBuilder pb = new PathBuilder();
        appendPathString(pb, s);
        return pb.result();
    }
}
