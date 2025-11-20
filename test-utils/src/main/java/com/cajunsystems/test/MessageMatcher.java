package com.cajunsystems.test;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Utilities for sophisticated message pattern matching in tests.
 * Provides composable predicates for message assertions.
 * 
 * <p>Usage:
 * <pre>{@code
 * // Match by type
 * Predicate<Object> matcher = MessageMatcher.instanceOf(Response.class);
 * 
 * // Combine multiple conditions
 * Predicate<Response> matcher = MessageMatcher.allOf(
 *     MessageMatcher.instanceOf(Response.class),
 *     response -> response.value() > 0,
 *     response -> response.status().equals("OK")
 * );
 * 
 * // Match any condition
 * Predicate<Object> matcher = MessageMatcher.anyOf(
 *     MessageMatcher.instanceOf(ErrorResponse.class),
 *     MessageMatcher.instanceOf(TimeoutResponse.class)
 * );
 * }</pre>
 */
public class MessageMatcher {
    
    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private MessageMatcher() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates a predicate that matches instances of the specified type.
     * 
     * @param <T> the message type
     * @param type the class to match
     * @return a predicate that matches instances of the type
     */
    public static <T> Predicate<T> instanceOf(Class<?> type) {
        Objects.requireNonNull(type, "type cannot be null");
        return obj -> type.isInstance(obj);
    }
    
    /**
     * Creates a predicate that matches if ALL predicates match.
     * 
     * @param <T> the message type
     * @param predicates the predicates to combine
     * @return a predicate that matches if all predicates match
     */
    @SafeVarargs
    public static <T> Predicate<T> allOf(Predicate<T>... predicates) {
        Objects.requireNonNull(predicates, "predicates cannot be null");
        if (predicates.length == 0) {
            throw new IllegalArgumentException("At least one predicate required");
        }
        return obj -> Arrays.stream(predicates).allMatch(p -> p.test(obj));
    }
    
    /**
     * Creates a predicate that matches if ANY predicate matches.
     * 
     * @param <T> the message type
     * @param predicates the predicates to combine
     * @return a predicate that matches if any predicate matches
     */
    @SafeVarargs
    public static <T> Predicate<T> anyOf(Predicate<T>... predicates) {
        Objects.requireNonNull(predicates, "predicates cannot be null");
        if (predicates.length == 0) {
            throw new IllegalArgumentException("At least one predicate required");
        }
        return obj -> Arrays.stream(predicates).anyMatch(p -> p.test(obj));
    }
    
    /**
     * Creates a predicate that matches if the predicate does NOT match.
     * 
     * @param <T> the message type
     * @param predicate the predicate to negate
     * @return a predicate that matches if the predicate does not match
     */
    public static <T> Predicate<T> not(Predicate<T> predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        return predicate.negate();
    }
    
    /**
     * Creates a predicate that matches null values.
     * 
     * @param <T> the message type
     * @return a predicate that matches null values
     */
    public static <T> Predicate<T> isNull() {
        return Objects::isNull;
    }
    
    /**
     * Creates a predicate that matches non-null values.
     * 
     * @param <T> the message type
     * @return a predicate that matches non-null values
     */
    public static <T> Predicate<T> isNotNull() {
        return Objects::nonNull;
    }
    
    /**
     * Creates a predicate that matches objects equal to the specified value.
     * 
     * @param <T> the message type
     * @param expected the expected value
     * @return a predicate that matches equal values
     */
    public static <T> Predicate<T> equalTo(T expected) {
        return obj -> Objects.equals(obj, expected);
    }
    
    /**
     * Creates a predicate that matches objects not equal to the specified value.
     * 
     * @param <T> the message type
     * @param value the value to compare against
     * @return a predicate that matches non-equal values
     */
    public static <T> Predicate<T> notEqualTo(T value) {
        return obj -> !Objects.equals(obj, value);
    }
    
    /**
     * Creates a predicate that matches strings containing the specified substring.
     * 
     * @param substring the substring to search for
     * @return a predicate that matches strings containing the substring
     */
    public static Predicate<String> contains(String substring) {
        Objects.requireNonNull(substring, "substring cannot be null");
        return str -> str != null && str.contains(substring);
    }
    
    /**
     * Creates a predicate that matches strings starting with the specified prefix.
     * 
     * @param prefix the prefix to match
     * @return a predicate that matches strings with the prefix
     */
    public static Predicate<String> startsWith(String prefix) {
        Objects.requireNonNull(prefix, "prefix cannot be null");
        return str -> str != null && str.startsWith(prefix);
    }
    
    /**
     * Creates a predicate that matches strings ending with the specified suffix.
     * 
     * @param suffix the suffix to match
     * @return a predicate that matches strings with the suffix
     */
    public static Predicate<String> endsWith(String suffix) {
        Objects.requireNonNull(suffix, "suffix cannot be null");
        return str -> str != null && str.endsWith(suffix);
    }
    
    /**
     * Creates a predicate that matches strings matching the specified regex.
     * 
     * @param regex the regular expression to match
     * @return a predicate that matches strings matching the regex
     */
    public static Predicate<String> matches(String regex) {
        Objects.requireNonNull(regex, "regex cannot be null");
        return str -> str != null && str.matches(regex);
    }
    
    /**
     * Creates a predicate that always matches.
     * 
     * @param <T> the message type
     * @return a predicate that always returns true
     */
    public static <T> Predicate<T> any() {
        return obj -> true;
    }
    
    /**
     * Creates a predicate that never matches.
     * 
     * @param <T> the message type
     * @return a predicate that always returns false
     */
    public static <T> Predicate<T> none() {
        return obj -> false;
    }
}
