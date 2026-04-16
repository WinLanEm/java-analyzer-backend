public class Demo {
public static void main(String[] args) {
int sum = 0;

    for (int i = 1; i <= 2; i = i + 1) {
      int j = 1;
      
      while (j <= 2) {
        sum = sum + i * j;
        j = j + 1;
      }
    }
    
    System.out.println(sum);
}
}

public class Demo {
public static void main(String[] args) {
int score = 0;

    for (int i = 10; i <= 30; i = i + 10) {
      if (i == 30) {
        score = score + 100;
      } else if (i == 20) {
        score = score + 50;
      } else {
        score = score + 10;
      }
    }
    
    System.out.println(score);
}
}
public class Demo {
public static void main(String[] args) {
int count = 1;
int highScores = 0;
int lowScores = 0;

    while (count <= 4) {
      if (count >= 3) {
        highScores = highScores + 1;
      } else {
        lowScores = lowScores + 1;
      }
      count = count + 1;
    }
    
    System.out.println(highScores);
}
}