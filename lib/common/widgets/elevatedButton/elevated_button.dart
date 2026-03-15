
import 'package:flutter/material.dart';

import '../../../utils/helpers/device_helper.dart';

class UElevatedButton extends StatelessWidget {
  const UElevatedButton({
    super.key, required this.onPressed, required this.child
  });
  final Widget child;
  final VoidCallback onPressed;
  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: UDeviceHelper.getScreenWidth(context),
        child: ElevatedButton(onPressed: onPressed, child: child));
  }
}