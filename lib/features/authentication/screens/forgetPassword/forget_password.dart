
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../../common/textFormField/textFormField.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/validators/validation.dart';
import '../../controllers/forgetPassword/forgetPassword_controller.dart';

class ForgetPasswordScreen extends StatelessWidget {
  const ForgetPasswordScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = Get.put(ForgetPasswordController());
    return Scaffold(
      appBar: AppBar(),
      body: Padding(
        padding: const EdgeInsets.all(USizes.defaultSpace),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            //----[Header Title]----
            Text(UTexts.forgetPasswordTitle,style: Theme.of(context).textTheme.headlineMedium),

            //----[SubTitle]-----
            SizedBox(height: USizes.spaceBtwItems,),
            Text(UTexts.forgetPasswordSubTitle, style: Theme.of(context).textTheme.labelMedium,),

            //-----[TextField]----
            SizedBox(height: USizes.spaceBtwSections,),
            Form(
              key: controller.forgetPasswordForm,
              child: UTextFormField(
                validator: (value)=> UValidator.validateEmail(value),
                  controller: controller.email,
                  prefixIcon: Icons.send,
                  labelText: 'Email'
              ),
            ),

            //----[SubmitButton]----
            SizedBox(height: USizes.spaceBtwSections,),
            UElevatedButton(onPressed: controller.sendEmailForgetPassword,
                child: Text('Submit'))

          ],
        ),
      ),
    );
  }
}
