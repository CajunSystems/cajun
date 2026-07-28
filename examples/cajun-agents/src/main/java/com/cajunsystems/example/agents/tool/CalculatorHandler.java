package com.cajunsystems.example.agents.tool;

import com.cajunsystems.ActorContext;
import com.cajunsystems.example.agents.message.AgentRequest;
import com.cajunsystems.example.agents.message.ToolMessage;
import com.cajunsystems.handler.Handler;
import com.cajunsystems.spring.annotation.ActorComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Tool actor that evaluates basic arithmetic expressions.
 *
 * <p>Supports {@code +}, {@code -}, {@code *}, {@code /} and parentheses via a
 * simple recursive-descent parser (no external dependencies, safe for Java 21
 * which removed the Nashorn JavaScript engine).
 */
@ActorComponent(id = "calculator-tool")
public class CalculatorHandler implements Handler<ToolMessage> {

    private static final Logger log = LoggerFactory.getLogger(CalculatorHandler.class);

    @Override
    public void receive(ToolMessage message, ActorContext ctx) {
        if (!(message instanceof ToolMessage.Invoke invoke)) return;

        String expression = (String) invoke.args().getOrDefault("expression", "0");
        log.info("[calculator-tool] Evaluating: {}", expression);

        String result = evaluate(expression);

        ctx.tell(invoke.callbackPid(), new AgentRequest.ToolResult(
                invoke.requestId(), invoke.toolCallId(), "calculator", result));
    }

    // ------------------------------------------------------------------ simple evaluator

    private String evaluate(String expr) {
        try {
            // Strip anything that isn't a digit, operator, dot, or parenthesis.
            String sanitized = expr.replaceAll("[^0-9+\\-*/.()\\s]", "").trim();
            if (sanitized.isEmpty()) return "Invalid expression: " + expr;
            double result = parseExpr(new TokenStream(sanitized));
            // Format: omit decimal if it's a whole number.
            String formatted = (result == Math.floor(result) && !Double.isInfinite(result))
                    ? String.valueOf((long) result)
                    : String.valueOf(result);
            return sanitized + " = " + formatted;
        } catch (Exception e) {
            return "Error evaluating '" + expr + "': " + e.getMessage();
        }
    }

    // Recursive descent: expr → term (('+' | '-') term)*
    private double parseExpr(TokenStream ts) {
        double result = parseTerm(ts);
        while (ts.peek() == '+' || ts.peek() == '-') {
            char op = ts.next();
            double right = parseTerm(ts);
            result = op == '+' ? result + right : result - right;
        }
        return result;
    }

    // term → factor (('*' | '/') factor)*
    private double parseTerm(TokenStream ts) {
        double result = parseFactor(ts);
        while (ts.peek() == '*' || ts.peek() == '/') {
            char op = ts.next();
            double right = parseFactor(ts);
            result = op == '*' ? result * right : result / right;
        }
        return result;
    }

    // factor → number | '(' expr ')'
    private double parseFactor(TokenStream ts) {
        ts.skipSpaces();
        if (ts.peek() == '(') {
            ts.next(); // consume '('
            double val = parseExpr(ts);
            ts.skipSpaces();
            if (ts.peek() == ')') ts.next(); // consume ')'
            return val;
        }
        return ts.readNumber();
    }

    private static class TokenStream {
        private final String s;
        private int pos;

        TokenStream(String s) { this.s = s; this.pos = 0; }

        char peek() {
            skipSpaces();
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        char next() { skipSpaces(); return s.charAt(pos++); }

        void skipSpaces() { while (pos < s.length() && s.charAt(pos) == ' ') pos++; }

        double readNumber() {
            skipSpaces();
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
