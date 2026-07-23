<<<<<<< HEAD

import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:get/get_core/src/get_main.dart';
=======
import 'package:flutter/material.dart';
import 'package:get/get.dart';
>>>>>>> workspace
import '../../../../common/dividers/divider_widget.dart';
import '../../../../common/icons/icon_widget.dart';
import '../../../../common/textFormField/textFormField.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../utils/constants/sizes.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/validators/validation.dart';
import '../../controllers/login/login_controller.dart';
import '../forgetPassword/forget_password.dart';
<<<<<<< HEAD
import '../signUp/signUp_Screen.dart';
=======
>>>>>>> workspace

class LoginScreen extends StatelessWidget {
  const LoginScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = Get.put(LoginController());
    return Scaffold(
<<<<<<< HEAD
      appBar: AppBar(automaticallyImplyLeading: true,),
=======
      appBar: AppBar(automaticallyImplyLeading: true),
>>>>>>> workspace
      body: SingleChildScrollView(
        child: Padding(
          padding: EdgeInsets.all(USizes.defaultSpace),
          child: Form(
            key: controller.loginFormKey,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
<<<<<<< HEAD
                Text('Sign In',style: Theme.of(context).textTheme.headlineLarge,),
                SizedBox(height: USizes.spaceBtwInputFields,),
=======
                Text(
                  'Sign In',
                  style: Theme.of(context).textTheme.headlineLarge,
                ),
                SizedBox(height: USizes.spaceBtwInputFields),
>>>>>>> workspace

                //----[Text FormFields]------
                Column(
                  children: [
                    UTextFormField(
                      validator: (value) => UValidator.validateEmail(value),
<<<<<<< HEAD
                        controller: controller.email,
                        prefixIcon: Icons.mail_outline,
                        labelText: 'Email'
                    ),

                    SizedBox(height: USizes.spaceBtwInputFields,),

                    Obx(
                      () =>  UTextFormField(
                        validator: (value) => UValidator.validateEmptyText('Password', value),
                        controller: controller.password,
                        prefixIcon: Icons.password,
                        obscureText: controller.isPasswordVisible.value,
                        suffixIcon: IconButton(onPressed: () => controller.isPasswordVisible.toggle(),
                            icon: Icon(controller.isPasswordVisible.value ? Icons.visibility_off : Icons.visibility)
=======
                      controller: controller.email,
                      prefixIcon: Icons.mail_outline,
                      labelText: 'Email',
                    ),

                    SizedBox(height: USizes.spaceBtwInputFields),

                    Obx(
                      () => UTextFormField(
                        validator: (value) =>
                            UValidator.validateEmptyText('Password', value),
                        controller: controller.password,
                        prefixIcon: Icons.password,
                        obscureText: controller.isPasswordVisible.value,
                        suffixIcon: IconButton(
                          onPressed: () =>
                              controller.isPasswordVisible.toggle(),
                          icon: Icon(
                            controller.isPasswordVisible.value
                                ? Icons.visibility_off
                                : Icons.visibility,
                          ),
>>>>>>> workspace
                        ),
                        labelText: 'Password',
                      ),
                    ),

                    //-----[CheckBox, Remember me, Forget Password]-----
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
<<<<<<< HEAD
                            Obx( ()=>  Checkbox(value: controller.isRememberMe.value, onChanged: (value)=> controller.isRememberMe.toggle())),
                            Text(UTexts.rememberMe)
                          ],
                        ),
                        TextButton(onPressed: (){
                          Get.to(()=> ForgetPasswordScreen());
                        }, child: Text(UTexts.forgetPassword))
                      ],
                    ),

=======
                            Obx(
                              () => Checkbox(
                                value: controller.isRememberMe.value,
                                onChanged: (value) =>
                                    controller.isRememberMe.toggle(),
                              ),
                            ),
                            Text(UTexts.rememberMe),
                          ],
                        ),
                        TextButton(
                          onPressed: () {
                            Get.to(() => ForgetPasswordScreen());
                          },
                          child: Text(UTexts.forgetPassword),
                        ),
                      ],
                    ),
>>>>>>> workspace
                  ],
                ),

                //-----[Sigh In, Create Account Button]-----
<<<<<<< HEAD
                SizedBox(height: USizes.spaceBtwSections,),

                UElevatedButton(onPressed: controller.loginWithEmailPassword,
                    child: Text(UTexts.signIn)
                ),



                //---[Divider]----
                SizedBox(height: USizes.spaceBtwSections/2),
                UItemDividerWidget(),

                // Google & Facebook Button
                SizedBox(height: USizes.spaceBtwSections/2),
                UIconWidget()
=======
                SizedBox(height: USizes.spaceBtwSections),

                UElevatedButton(
                  onPressed: controller.loginWithEmailPassword,
                  child: Text(UTexts.signIn),
                ),

                //---[Divider]----
                SizedBox(height: USizes.spaceBtwSections / 2),
                UItemDividerWidget(),

                // Google & Facebook Button
                SizedBox(height: USizes.spaceBtwSections / 2),
                UIconWidget(),
>>>>>>> workspace
              ],
            ),
          ),
        ),
      ),
    );
  }
}
<<<<<<< HEAD






=======
>>>>>>> workspace
