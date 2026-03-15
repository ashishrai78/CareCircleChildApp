
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:get/get_core/src/get_main.dart';

import '../../../../common/dividers/divider_widget.dart';
import '../../../../common/icons/icon_widget.dart';
import '../../../../common/textFormField/textFormField.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../utils/constants/colors.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/validators/validation.dart';
import '../../controllers/signUp/signUp_controller.dart';

class SignupScreen extends StatelessWidget {
  const SignupScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = Get.put(SignUpController());
    return Scaffold(
      appBar: AppBar(),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(USizes.defaultSpace),
          child: Form(
            key: controller.signUpFormKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [

                //-----[SignUp Title Text]-----
                Text(
                  UTexts.signupTitle,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),

                //------[TextFormFields]----
                SizedBox(
                  height: USizes.spaceBtwSections,
                ),

                //-----[TextField for UserName]----
                UTextFormField(
                    controller: controller.firstName,
                    validator: (value) => UValidator.validateEmptyText('Name', value),
                    prefixIcon: Icons.person,
                    labelText: 'Name'
                ),


                //-----[Other textFields]-----
                SizedBox(
                  height: USizes.spaceBtwInputFields,
                ),
                
                
                UTextFormField(
                    controller: controller.email,
                    validator: (value) => UValidator.validateEmail(value),
                    prefixIcon: Icons.mail_outline,
                    labelText: 'Email'
                ),

                SizedBox(
                  height: USizes.spaceBtwInputFields,
                ),

                Obx(
                    () =>  UTextFormField(
                      obscureText: controller.isPasswordVisible.value,
                      controller: controller.password,
                      validator: (value) => UValidator.validatePassword(value),
                      prefixIcon: Icons.password,
                      suffixIcon: IconButton(onPressed: ()=> controller.isPasswordVisible.value = !controller.isPasswordVisible.value,
                          icon: Icon(controller.isPasswordVisible.value ? Icons.visibility_off : Icons.visibility)
                      ),
                      labelText: 'Password'
                  ),
                ),
                
                SizedBox(
                  height: USizes.spaceBtwInputFields,
                ),


                //-----[CheckBox or Texts]-----
                Row(
                  children: [
                    Obx(() => Checkbox(value: controller.privacyPolicy.value, onChanged: (value) => controller.privacyPolicy.value = !controller.privacyPolicy.value)),
                    RichText(
                        text: TextSpan(
                            style: Theme.of(context).textTheme.bodyMedium,
                            children: [
                          TextSpan(text: '${UTexts.iAgreeTo} '),
                          TextSpan(
                              text: '${UTexts.privacyPolicy} ',
                              style: Theme.of(context)
                                  .textTheme
                                  .bodyMedium!
                                  .copyWith(
                                      color: UColors.primary,
                                      decoration: TextDecoration.underline,
                                      decorationColor: UColors.primary)),
                          TextSpan(text: '${UTexts.and} '),
                          TextSpan(
                              text: '${UTexts.termsOfUse} ',
                              style: Theme.of(context)
                                  .textTheme
                                  .bodyMedium!
                                  .copyWith(
                                      color: UColors.primary,
                                      decoration: TextDecoration.underline,
                                      decorationColor: UColors.primary))
                        ]))
                  ],
                ),


                //-----[Create Account Button]----
                SizedBox(height: USizes.spaceBtwSections,),
                UElevatedButton(onPressed: (){
                  controller.registerUser();
                }, child: Text(UTexts.createAccount)
                ),


                //----[Divider]-----
                SizedBox(height: USizes.spaceBtwSections/2,),
                UItemDividerWidget(),


                ///-----[IconBtn Google, FaceBook]----
                SizedBox(height: USizes.spaceBtwSections/2,),
                UIconWidget()
              ],
            ),
          ),
        ),
      ),
    );
  }
}
