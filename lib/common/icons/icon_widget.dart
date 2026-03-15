
import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../features/authentication/controllers/login/login_controller.dart';
import '../../utils/constants/colors.dart';
import '../../utils/constants/images.dart';
import '../../utils/constants/sizes.dart';
import '../../utils/helpers/device_helper.dart';

class UIconWidget extends StatelessWidget {
  const UIconWidget({
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    final controller = Get.put(LoginController());
    return GestureDetector(
      onTap: controller.googleSignIn,
      child: Container(
        width: UDeviceHelper.getScreenWidth(context),
        decoration: BoxDecoration(
            border: Border.all(color: UColors.grey,width: 2),
            borderRadius: BorderRadius.circular(12)
        ),
        child: Padding(
          padding: const EdgeInsets.all(10.0),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
            Image.asset(UImages.loginGoogleLogo, height: USizes.iconMd, width: USizes.iconMd,),
            SizedBox(width: 5,),
            Text('Google')
          ],),
        )
      ),
    );
  }
}