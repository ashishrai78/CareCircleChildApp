
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../utils/constants/images.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/helpers/device_helper.dart';
import '../../controllers/forgetPassword/forgetPassword_controller.dart';
import '../login/login_screen.dart';

class ResetPasswordScreen extends StatelessWidget {
  const ResetPasswordScreen({super.key, required this.email});

  final String email;
  @override
  Widget build(BuildContext context) {
    final controller = ForgetPasswordController.instance;
    return Scaffold(
      appBar: AppBar(automaticallyImplyLeading: false,
      actions: [
        IconButton(onPressed: (){
          Get.offAll(()=> LoginScreen());
        }, icon: Icon(Icons.clear))
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
              Text(UTexts.resetPasswordTitle, style: Theme.of(context).textTheme.headlineMedium,),

              //---[Email]----
              SizedBox(height: USizes.spaceBtwItems,),
              Text(email, style: Theme.of(context).textTheme.bodyMedium,),
              
              //----[title]----
              SizedBox(height: USizes.spaceBtwItems,),
              Text(UTexts.resetPasswordSubTitle, style: Theme.of(context).textTheme.bodyLarge,),
              
              //----[Button]----
              SizedBox(height: USizes.spaceBtwSections,),
              UElevatedButton(onPressed: ()=> Get.offAll(()=> LoginScreen())
                  ,child: Text('Done')),
              
              //----[Resend Email]----
              SizedBox(height: USizes.spaceBtwItems,),
              TextButton(onPressed: controller.resendSendEmailForgetPassword, child: Text(UTexts.resendEmail))
            ],
          ),
        ),
      ),
    );
  }
}
