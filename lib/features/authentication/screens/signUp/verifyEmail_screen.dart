
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../data/repositories/authentication/authentication_repository.dart';
import '../../../../utils/constants/images.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/helpers/device_helper.dart';
import '../../controllers/signUp/emailVerification.dart';

class VerifyEmailScreen extends StatelessWidget {
  const VerifyEmailScreen({super.key, this.userEmail});

  final String? userEmail;
  @override
  Widget build(BuildContext context) {
    final controller = Get.put(EmailVerification());
    return Scaffold(
      appBar: AppBar(automaticallyImplyLeading: false,
        actions: [
          IconButton(onPressed: AuthenticationRepository.instance.logOut,
          icon: Icon(Icons.clear))
        ],
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(USizes.defaultSpace),
          child: Column(
            children: [
              //-----[sendEmailImage]-----
              Image.asset(UImages.sendEmailImage, height: UDeviceHelper.getScreenWidth(context)*0.7,),

              ///---[header]----
              SizedBox(height: USizes.spaceBtwSections,),
              Text(UTexts.verifyEmailTitle, style: Theme.of(context).textTheme.headlineMedium,),

              //---[Email]----
              SizedBox(height: USizes.spaceBtwItems,),
              Text(userEmail ?? '', style: Theme.of(context).textTheme.bodyMedium,),

              //----[title]----
              SizedBox(height: USizes.spaceBtwItems,),
              Text(UTexts.verifyEmailSubTitle, style: Theme.of(context).textTheme.bodyLarge, textAlign: TextAlign.center,),

              //----[Button]----
              SizedBox(height: USizes.spaceBtwSections,),
              UElevatedButton(onPressed: controller.checkEmailVerificationStatus,
              child: Text(UTexts.uContinue)),

              //----[Resend Email]----
              SizedBox(height: USizes.spaceBtwItems,),
              TextButton(onPressed: controller.sendEmailVerification, child: Text(UTexts.resendEmail))
            ],
          ),
        ),
      ),
    );
  }
}
