
import 'package:flutter/material.dart';

import '../../../utils/constants/sizes.dart';

class URoundedImages extends StatelessWidget {
  const URoundedImages({
    super.key,
    this.onPressed,
    this.width,
    this.height,
    this.border,
    this.backgroundColor,
    this.padding,
    this.borderRadius = USizes.md,
    required this.imageUrl,
    this.isNetworkImage = false,
    this.applyImageRadius = true,
    this.fit = BoxFit.contain,
  });

  final VoidCallback? onPressed;
  final double? width, height;
  final BoxBorder? border;
  final Color? backgroundColor;
  final EdgeInsetsGeometry? padding;
  final double borderRadius;
  final String imageUrl;
  final bool isNetworkImage;
  final bool applyImageRadius;
  final BoxFit? fit;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onPressed,
      child: Container(
        width: width,
        height: height,
        padding: padding,
        decoration: BoxDecoration(
          border: border,
          color: backgroundColor,
          borderRadius: BorderRadius.circular(borderRadius),
        ),
        child: ClipRRect(
            borderRadius: applyImageRadius
                ? BorderRadius.circular(borderRadius)
                : BorderRadius.zero,
            child: Image(
              image:
              isNetworkImage ? NetworkImage(imageUrl) : AssetImage(imageUrl),
              fit: fit,
            )),
      ),
    );
  }
}