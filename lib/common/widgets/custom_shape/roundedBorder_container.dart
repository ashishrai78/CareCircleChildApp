
import 'package:flutter/material.dart';

import '../../../utils/constants/colors.dart';

class URoundedContainer extends StatelessWidget {
  const URoundedContainer(
      {super.key,
        this.height,
        this.width,
        this.color,
        this.margin,
        this.borderRadius,
        this.child,
        this.border = false, this.onTap,

      });

  final double? height, width;
  final Color? color;
  final BorderRadius? borderRadius;
  final bool border;
  final EdgeInsets? margin;
  final Widget? child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Container(
        height: height,
        width: width,
        margin: margin,
        decoration: BoxDecoration(
            color: color,
            borderRadius: borderRadius,
            border: border ? Border.all(color: UColors.grey, width: 2): null),
        child: child,
      ),
    );
  }
}
