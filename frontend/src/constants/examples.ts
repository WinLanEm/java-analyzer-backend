export const examples = {
    'Basic Loop': `public class Demo {
  public static void main(String[] args) {
    int x = 10;
    for (int i = 0; i < 3; i++) {
      x = x + i;
    }
    System.out.println(x);
  }
}`,
    'Array Sum': `public class Demo {
  public static void main(String[] args) {
    int[] numbers = new int[] {10, 20, 30};
    int sum = 0;
    for (int i = 0; i < numbers.length; i++) {
      sum = sum + numbers[i];
    }
    System.out.println(sum);
  }
}`,
    'Warehouse Robot': `class Battery {
  int charge = 20;
}

class WarehouseRobot {
  private Battery battery = new Battery();

  int pick(int weight) {
    if (weight > battery.charge) {
      throw new RuntimeException();
    }
    battery.charge = battery.charge - weight;
    return battery.charge;
  }
}

public class Demo {
  public static void main(String[] args) {
    WarehouseRobot robot = new WarehouseRobot();
    int remaining = robot.pick(30);
    System.out.println(remaining);
  }
}`
} as const;

export type ExampleKey = keyof typeof examples;