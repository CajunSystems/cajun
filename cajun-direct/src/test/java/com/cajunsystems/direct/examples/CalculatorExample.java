package com.cajunsystems.direct.examples;

import com.cajunsystems.direct.*;

import java.time.Duration;

/**
 * Example showing a stateless calculator actor with direct-style calls.
 *
 * <p>Demonstrates:
 * <ul>
 *   <li>Sealed interfaces as message types</li>
 *   <li>Pattern matching in handlers</li>
 *   <li>Blocking {@link DirectPid#call} semantics</li>
 *   <li>Async {@link DirectPid#callAsync} for composition</li>
 * </ul>
 */
public class CalculatorExample {

    // Message protocol using sealed interfaces + records
    sealed interface CalcMsg {
        record Add(double a, double b) implements CalcMsg {}
        record Subtract(double a, double b) implements CalcMsg {}
        record Multiply(double a, double b) implements CalcMsg {}
        record Divide(double a, double b) implements CalcMsg {}
    }

    // Handler: returns a value directly — no callbacks, no future chaining
    static class CalculatorHandler implements DirectHandler<CalcMsg, Double> {
        @Override
        public Double handle(CalcMsg msg, DirectContext context) {
            return switch (msg) {
                case CalcMsg.Add(double a, double b)      -> a + b;
                case CalcMsg.Subtract(double a, double b) -> a - b;
                case CalcMsg.Multiply(double a, double b) -> a * b;
                case CalcMsg.Divide(double a, double b)   -> {
                    if (b == 0) throw new ArithmeticException("Division by zero");
                    yield a / b;
                }
            };
        }

        @Override
        public Double onError(CalcMsg msg, Throwable exception, DirectContext context) {
            context.getLogger().error("Calculator error for {}: {}", msg, exception.getMessage());
            return Double.NaN;
        }
    }

    public static void main(String[] args) {
        DirectActorSystem system = new DirectActorSystem();

        DirectPid<CalcMsg, Double> calc = system.actorOf(new CalculatorHandler())
                .withId("calculator")
                .withTimeout(Duration.ofSeconds(5))
                .spawn();

        // Direct, blocking calls — read like method invocations
        System.out.println("3 + 4 = " + calc.call(new CalcMsg.Add(3, 4)));          // 7.0
        System.out.println("10 - 3 = " + calc.call(new CalcMsg.Subtract(10, 3)));   // 7.0
        System.out.println("6 × 7 = " + calc.call(new CalcMsg.Multiply(6, 7)));     // 42.0
        System.out.println("15 / 4 = " + calc.call(new CalcMsg.Divide(15, 4)));     // 3.75

        // Error recovery: division by zero returns NaN
        System.out.println("1 / 0 = " + calc.call(new CalcMsg.Divide(1, 0)));       // NaN

        // Async composition
        calc.callAsync(new CalcMsg.Add(100, 200))
                .thenApply(result -> "Async: 100 + 200 = " + result)
                .thenAccept(System.out::println);

        // Virtual thread usage — multiple calls on parallel virtual threads
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int n = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                double result = calc.call(new CalcMsg.Multiply(n, n));
                System.out.printf("Virtual thread: %d² = %.0f%n", n, result);
            });
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        system.shutdown();
    }
}
