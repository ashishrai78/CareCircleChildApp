import 'package:flutter/material.dart';

import '../../../utils/constants/colors.dart';

class UCircularContainer extends StatelessWidget {
  const UCircularContainer({
    super.key, required this.height, required this.width, this.color, this.child,
  });

  final double height, width;
  final Color? color;
  final Widget? child;
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: EdgeInsets.zero,
        height: height,
        width: width,
        decoration: BoxDecoration(
            color: color,
            shape: BoxShape.circle,
            border: Border.all(
                color: UColors.primary,
                width: 5
            )
        ),
        child: child
    );
  }
}