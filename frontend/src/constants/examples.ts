export const examples = {
    '1. Basics': `public class Demo {
  public static void main(String[] args) {
    int a = 15;
    int b = 5;
    int c = (a + b) * 2;
    int d = c / b;
    System.out.println(d);
  }
}`,

    '2. Nested Conditions': `public class Demo {
  public static void main(String[] args) {
    int score = 85;
    String grade = "F";

    if (score >= 90) {
      grade = "A";
    } else {
      if (score >= 80) {
        grade = "B";
      } else {
        grade = "C";
      }
    }
    
    System.out.println(grade);
  }
}`,

    '3. Array Reversal': `public class Demo {
  public static void main(String[] args) {
    int[] arr = new int[] {10, 20, 30, 40};
    int n = arr.length;
    
    for (int i = 0; i < n / 2; i++) {
      int temp = arr[i];
      arr[i] = arr[n - 1 - i];
      arr[n - 1 - i] = temp;
    }
  }
}`,

        '4. Recursion (Factorial)': `public class Demo {
  public static int factorial(int n) {
    if (n <= 1) {
      return 1;
    }
    
    int nextN = n - 1;
    int prevFact = factorial(nextN);
    return n * prevFact;
  }

  public static void main(String[] args) {
    int result = factorial(4);
    System.out.println(result);
  }
}`,

    '5. Memory & References': `class Point {
  int x;
  int y;
}

public class Demo {
  public static void main(String[] args) {
    Point p1 = new Point();
    p1.x = 10;
    p1.y = 20;

    // p2 указывает на тот же объект в Heap!
    Point p2 = p1; 
    p2.x = 999;

    // Выведет 999, потому что объект один и тот же
    System.out.println(p1.x); 
  }
}`,

    '6. Exceptions (Bank)': `class BankAccount {
  int balance = 100;

  void withdraw(int amount) {
    if (amount > this.balance) {
      throw new RuntimeException();
    }
    this.balance = this.balance - amount;
  }
}

public class Demo {
  public static void main(String[] args) {
    BankAccount acc = new BankAccount();
    acc.withdraw(40);
    
    acc.withdraw(100); 
  }
}`,

    '7. The Boss (E-Commerce)': `class Product {
  int id;
  int price;
}

class Cart {
  Product[] items = new Product[2];
  int count = 0;

  void add(Product p) {
    // 1. Вытаскиваем поля в локальные переменные (для парсера и наглядности)
    Product[] localItems = this.items;
    int currentCount = this.count;

    if (currentCount >= localItems.length) {
      throw new RuntimeException();
    }
    
    // 2. Работаем с локальными копиями
    localItems[currentCount] = p;
    
    // 3. Сохраняем обновленный счетчик обратно в Heap
    this.count = currentCount + 1;
  }

  int calculateTotal() {
    int sum = 0;
    Product[] localItems = this.items;
    int currentCount = this.count;

    for (int i = 0; i < currentCount; i++) {
      Product item = localItems[i];
      int itemPrice = item.price;
      
      // Скидка 10 монет на дорогие товары
      if (itemPrice >= 100) {
        itemPrice = itemPrice - 10; 
      }
      sum = sum + itemPrice;
    }
    return sum;
  }
}

public class Demo {
  public static void main(String[] args) {
    Cart cart = new Cart();

    Product p1 = new Product();
    p1.id = 1;
    p1.price = 50;

    Product p2 = new Product();
    p2.id = 2;
    p2.price = 150;

    // Добавляем товары (работа с массивами и ссылками)
    cart.add(p1);
    cart.add(p2);

    // Считаем сумму (циклы и условия)
    int total = cart.calculateTotal();
    System.out.println(total);

    // Попытка переполнить корзину (вызовет Exception)
    Product p3 = new Product();
    cart.add(p3);
  }
}`
} as const;

export type ExampleKey = keyof typeof examples;