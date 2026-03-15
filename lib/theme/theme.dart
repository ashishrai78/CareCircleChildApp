
import 'package:background/theme/widgets_theme/checkbox_theme.dart';
import 'package:background/theme/widgets_theme/chip_theme.dart';
import 'package:background/theme/widgets_theme/elevated_button.dart';
import 'package:background/theme/widgets_theme/text_theme.dart';
import 'package:background/theme/widgets_theme/textfield_theme.dart';
import 'package:flutter/material.dart';

import '../utils/constants/colors.dart';

class UAppTheme {
  // private constructor
  UAppTheme._();

  static ThemeData lightTheme = ThemeData(
    useMaterial3: true,
    fontFamily: 'Nunito',
    brightness: Brightness.light,
    primaryColor: UColors.primary,
    disabledColor: UColors.grey,
    textTheme: UTextTheme.lightTextTheme,
    chipTheme: UChipTheme.lightChipTheme,
    scaffoldBackgroundColor: UColors.white,
    checkboxTheme: UCheckboxTheme.lightCheckboxTheme,
    elevatedButtonTheme: UElevatedButtonTheme.lightElevatedButtonTheme,
    inputDecorationTheme: UTextFormFieldTheme.lightInputDecorationTheme,
  );
}
