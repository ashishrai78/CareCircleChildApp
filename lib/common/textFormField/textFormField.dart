
import 'package:flutter/material.dart';

import '../../utils/constants/colors.dart';

class UTextFormField extends StatelessWidget {
  const UTextFormField({
    super.key, required this.prefixIcon, required this.labelText, this.validator, this.controller, this.suffixIcon, this.obscureText = false
  });

  final String labelText;
  final IconData prefixIcon;
  final Widget? suffixIcon;
  final bool obscureText;
  final FormFieldValidator? validator;
  final TextEditingController? controller;

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      validator: validator,
      obscureText: obscureText,
      decoration: InputDecoration(
          prefixIcon: Icon(prefixIcon),
          suffixIcon: suffixIcon,
          labelText: labelText,
          focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(10),
              borderSide: BorderSide(color: UColors.primary, width: 1.5)
          )
      ),
    );
  }
}