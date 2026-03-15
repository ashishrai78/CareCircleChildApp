
import 'package:background/theme/theme.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'bindings/bindings.dart';
import 'features/authentication/screens/onBoarding/onBoarding_screen.dart';


class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return  GetMaterialApp(
        themeMode: ThemeMode.system,
        debugShowCheckedModeBanner: false,
        theme: UAppTheme.lightTheme,
        initialBinding: UBindings(),
        home: OnboardingScreen()
    );
  }
}
