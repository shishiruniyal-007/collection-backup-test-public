public class HelloWorld {
    public static void main(String[] args) {
        Calculator calc = new Calculator();
        System.out.println("2 + 3 = " + calc.add(2, 3));
        System.out.println("5 - 1 = " + calc.subtract(5, 1));
        System.out.println("4 * 6 = " + calc.multiply(4, 6));
        System.out.println("10 / 2 = " + calc.divide(10, 2));
        System.out.println("Hello, World!");
    }
}
