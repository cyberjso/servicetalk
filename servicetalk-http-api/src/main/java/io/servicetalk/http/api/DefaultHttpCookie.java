/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.api;

import java.util.Objects;
import javax.annotation.Nullable;

import static io.servicetalk.http.api.CharSequences.caseInsensitiveHashCode;
import static io.servicetalk.http.api.CharSequences.contentEqualsIgnoreCase;
import static io.servicetalk.http.api.CharSequences.newAsciiString;
import static io.servicetalk.http.api.HeaderUtils.validateCookieTokenAndHeaderName;
import static java.lang.Long.parseLong;
import static java.util.Objects.requireNonNull;

final class DefaultHttpCookie implements HttpCookie {
    /**
     * An underlying size of 8 has been shown with the current {@link AsciiBuffer} hash algorithm to have no collisions
     * with the current set of supported cookie names. If more cookie names are supported, or the hash algorithm changes
     * this initial value should be re-evaluated.
     * <p>
     * We use {@link HttpHeaders} here because we need a case-insensitive way to compare multiple keys. Instead of a
     * if/else block we lean on {@link HttpHeaders} which provides an associative array that compares keys in a case
     * case-insensitive manner.
     */
    private static final HttpHeaders AV_FIELD_NAMES = new DefaultHttpHeaders(8, false, false);

    static {
        AV_FIELD_NAMES.add(newAsciiString("path"), new ParseStateCharSequence(ParseState.ParsingPath));
        AV_FIELD_NAMES.add(newAsciiString("domain"), new ParseStateCharSequence(ParseState.ParsingDomain));
        AV_FIELD_NAMES.add(newAsciiString("expires"), new ParseStateCharSequence(ParseState.ParsingExpires));
        AV_FIELD_NAMES.add(newAsciiString("max-age"), new ParseStateCharSequence(ParseState.ParsingMaxAge));
    }

    private final CharSequence name;
    private final CharSequence value;
    @Nullable
    private final CharSequence path;
    @Nullable
    private final CharSequence domain;
    @Nullable
    private final CharSequence expires;
    @Nullable
    private final Long maxAge;
    private final boolean wrapped;
    private final boolean secure;
    private final boolean httpOnly;

    DefaultHttpCookie(final CharSequence name, final CharSequence value, @Nullable final CharSequence path,
                      @Nullable final CharSequence domain, @Nullable final CharSequence expires,
                      @Nullable final Long maxAge, final boolean wrapped, final boolean secure, final boolean httpOnly) {
        this.name = requireNonNull(name);
        this.value = requireNonNull(value);
        this.path = path;
        this.domain = domain;
        this.expires = expires;
        this.maxAge = maxAge;
        this.wrapped = wrapped;
        this.secure = secure;
        this.httpOnly = httpOnly;
    }

    @Override
    public CharSequence name() {
        return name;
    }

    @Override
    public CharSequence value() {
        return value;
    }

    @Override
    public boolean isWrapped() {
        return wrapped;
    }

    @Nullable
    @Override
    public CharSequence domain() {
        return domain;
    }

    @Nullable
    @Override
    public CharSequence path() {
        return path;
    }

    @Nullable
    @Override
    public Long maxAge() {
        return maxAge;
    }

    @Nullable
    @Override
    public CharSequence expires() {
        return expires;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public boolean isHttpOnly() {
        return httpOnly;
    }

    @Override
    public boolean equals(final Object o) {
        if (!(o instanceof HttpCookie)) {
            return false;
        }
        final HttpCookie rhs = (HttpCookie) o;
        // It is not possible to do domain [1] and path [2] equality and preserve the equals/hashCode API because the
        // equality comparisons in the RFC are variable so we cannot guarantee the following property:
        // if equals(a) == equals(b) then a.hasCode() == b.hashCode()
        // [1] https://tools.ietf.org/html/rfc6265#section-5.1.3
        // [2] https://tools.ietf.org/html/rfc6265#section-5.1.4
        return contentEqualsIgnoreCase(name, rhs.name()) &&
                contentEqualsIgnoreCase(domain, rhs.domain()) &&
                Objects.equals(path, rhs.path());
    }

    @Override
    public int hashCode() {
        int hash = 31 + caseInsensitiveHashCode(name);
        if (domain != null) {
            hash = 31 * hash + caseInsensitiveHashCode(domain);
        }
        if (path != null) {
            hash = 31 * hash + path.hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + name + "]";
    }

    private enum ParseState {
        ParsingValue,
        ParsingPath,
        ParsingDomain,
        ParsingExpires,
        ParsingMaxAge,
        Unknown
    }

    private static final class ParseStateCharSequence implements CharSequence {
        final ParseState state;

        ParseStateCharSequence(final ParseState state) {
            this.state = state;
        }

        @Override
        public int length() {
            throw new UnsupportedOperationException();
        }

        @Override
        public char charAt(final int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharSequence subSequence(final int start, final int end) {
            throw new UnsupportedOperationException();
        }
    }

    static HttpCookie parseCookie(final CharSequence cookieHeaderValue, boolean validateContent) {
        CharSequence name = null;
        CharSequence value = null;
        CharSequence path = null;
        CharSequence domain = null;
        CharSequence expires = null;
        Long maxAge = null;
        boolean isWrapped = false;
        boolean isSecure = false;
        boolean isHttpOnly = false;
        ParseState parseState = ParseState.Unknown;
        int begin = 0;
        int i = 0;
        while (i < cookieHeaderValue.length()) {
            final char c = cookieHeaderValue.charAt(i);
            switch (c) {
                case '=':
                    if (name == null) {
                        if (i <= begin) {
                            throw new IllegalArgumentException("cookie name cannot be null or empty");
                        }
                        name = cookieHeaderValue.subSequence(begin, i);
                        if (validateContent) {
                            validateCookieTokenAndHeaderName(name);
                        }
                        parseState = ParseState.ParsingValue;
                    } else if (parseState == ParseState.Unknown) {
                        final CharSequence avName = cookieHeaderValue.subSequence(begin, i);
                        final CharSequence newState = AV_FIELD_NAMES.get(avName);
                        if (newState != null) {
                            parseState = ((ParseStateCharSequence) newState).state;
                        }
                    } else {
                        throw new IllegalArgumentException("unexpected = at index: " + i);
                    }
                    ++i;
                    begin = i;
                    break;
                case '"':
                    if (parseState == ParseState.ParsingValue) {
                        if (isWrapped) {
                            parseState = ParseState.Unknown;
                            value = cookieHeaderValue.subSequence(begin, i);
                            // Increment by 3 because we are skipping DQUOTE SEMI SP
                            i += 3;
                            begin = i;
                        } else {
                            isWrapped = true;
                            ++i;
                            begin = i;
                        }
                    } else if (value == null) {
                        throw new IllegalArgumentException("unexpected quote at index: " + i);
                    }
                    ++i;
                    break;
                case '%':
                    if (validateContent) {
                        extractAndValidateCookieHexValue(cookieHeaderValue, i);
                    }
                    // Increment by 4 because we are skipping %0x##
                    i += 4;
                    break;
                case ';':
                    // end of value, or end of av-value
                    if (i + 1 == cookieHeaderValue.length()) {
                        throw new IllegalArgumentException("unexpected trailing ';'");
                    }
                    switch (parseState) {
                        case ParsingValue:
                            value = cookieHeaderValue.subSequence(begin, i);
                            break;
                        case ParsingPath:
                            path = cookieHeaderValue.subSequence(begin, i);
                            break;
                        case ParsingDomain:
                            domain = cookieHeaderValue.subSequence(begin, i);
                            break;
                        case ParsingExpires:
                            expires = cookieHeaderValue.subSequence(begin, i);
                            break;
                        case ParsingMaxAge:
                            maxAge = parseLong(cookieHeaderValue.subSequence(begin, i).toString());
                            break;
                        default:
                            if (name == null) {
                                throw new IllegalArgumentException("cookie value not found at index " + i);
                            }
                            final CharSequence avName = cookieHeaderValue.subSequence(begin, i);
                            if (contentEqualsIgnoreCase(avName, "secure")) {
                                isSecure = true;
                            } else if (contentEqualsIgnoreCase(avName, "httponly")) {
                                isHttpOnly = true;
                            }
                            break;
                    }
                    parseState = ParseState.Unknown;
                    i += 2;
                    begin = i;
                    break;
                default:
                    if (validateContent && parseState != ParseState.ParsingExpires) {
                        validateCookieOctetHexValue(c);
                    }
                    ++i;
                    break;
            }
        }

        if (begin < i) {
            // end of value, or end of av-value
            // check for "secure" and "httponly"
            switch (parseState) {
                case ParsingValue:
                    value = cookieHeaderValue.subSequence(begin, i);
                    break;
                case ParsingPath:
                    path = cookieHeaderValue.subSequence(begin, i);
                    break;
                case ParsingDomain:
                    domain = cookieHeaderValue.subSequence(begin, i);
                    break;
                case ParsingExpires:
                    expires = cookieHeaderValue.subSequence(begin, i);
                    break;
                case ParsingMaxAge:
                    maxAge = parseLong(cookieHeaderValue.subSequence(begin, i).toString());
                    break;
                default:
                    if (name == null) {
                        throw new IllegalArgumentException("cookie value not found at index " + i);
                    }
                    final CharSequence avName = cookieHeaderValue.subSequence(begin, i);
                    if (contentEqualsIgnoreCase(avName, "secure")) {
                        isSecure = true;
                    } else if (contentEqualsIgnoreCase(avName, "httponly")) {
                        isHttpOnly = true;
                    }
                    break;
            }
        }

        return new DefaultHttpCookie(name, value, path, domain, expires, maxAge, isWrapped, isSecure, isHttpOnly);
    }

    /**
     * Extract a hex value and validate according to the
     * <a href="https://tools.ietf.org/html/rfc6265#section-4.1.1">cookie-octet</a> format.
     *
     * @param cookieHeaderValue The cookie's value.
     * @param i The index where we detected a '%' character indicating a hex value is to follow.
     */
    private static void extractAndValidateCookieHexValue(final CharSequence cookieHeaderValue, final int i) {
        if (cookieHeaderValue.length() - 3 <= i) {
            throw new IllegalArgumentException("invalid hex encoded value");
        }
        char c2 = cookieHeaderValue.charAt(i + 1);
        if (c2 != 'X' && c2 != 'x') {
            throw new IllegalArgumentException("unexpected hex indicator " + c2);
        }
        c2 = cookieHeaderValue.charAt(i + 2);
        final char c3 = cookieHeaderValue.charAt(i + 3);
        // The MSB can only be 0,1,2 so we do a cheaper conversion of hex -> decimal.
        final int hexValue = (c2 - '0') * 16 + hexToDecimal(c3);
        validateCookieOctetHexValue(hexValue);
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc6265#section-4.1.1">
     * cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E</a>
     *
     * @param hexValue The decimal representation of the hexadecimal value.
     */
    private static void validateCookieOctetHexValue(final int hexValue) {
        if (hexValue != 33 &&
                (hexValue < 35 || hexValue > 43) &&
                (hexValue < 45 || hexValue > 58) &&
                (hexValue < 60 || hexValue > 91) &&
                (hexValue < 93 || hexValue > 126)) {
            throw new IllegalArgumentException("unexpected hex value " + hexValue);
        }
    }

    private static int hexToDecimal(final char c) {
        return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? (c - 'a') + 10 : c >= 'A' && c < 'F' ?
                (c - 'A') + 10 : -1;
    }
}
