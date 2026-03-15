import 'dart:math';

class CodeGenerator {
  static String generate6DigitCode() {
    final random = Random();
    return (100000 + random.nextInt(900000)).toString();
  }
}
