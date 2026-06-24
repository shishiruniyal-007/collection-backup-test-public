public class Calculator {
    public int add(int a, int b) {
        return a + b;
    }

    public int subtract(int a, int b) {
        return a - b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }

    public int modulo(int a, int b) {
        return a%b;
    }

    public double divide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("Cannot divide by zero");
        }
        return (double) a / b;
    }

    public static void main(String[] args) {
        Calculator calc = new Calculator();
        System.out.println("2 + 3 = " + calc.add(2, 3));
        System.out.println("5 - 1 = " + calc.subtract(5, 1));
        System.out.println("4 * 6 = " + calc.multiply(4, 6));
        System.out.println("10 / 2 = " + calc.divide(10, 2));
    }
}
