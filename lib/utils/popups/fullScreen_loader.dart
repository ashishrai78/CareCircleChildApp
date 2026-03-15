import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../common/loaders/animation_loader.dart';
import '../constants/colors.dart';

class UFullScreenLoader {
  static void openLoadingDialog(String text) {
    showDialog(
        context: Get.overlayContext!,
        barrierDismissible: false,
        builder: (_) => PopScope(
            canPop: false,
            child: Container(
              color: UColors.white,
              width: double.infinity,
              height: double.infinity,
              child: Column(
                children: [
                  /// Extra Space
                  const SizedBox(height: 250),

                  /// Animation
                  UAnimationLoader(text: text)
                ],
              ),
            )));
  }

  static stopLoading() {
    Navigator.of(Get.overlayContext!).pop();
  }
}